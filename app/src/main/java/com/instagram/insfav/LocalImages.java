package com.instagram.insfav;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

/**
 * Read-only SAF catalog. Rescans never alter source files; SQLite checkpoints survive interruption.
 */
final class LocalImages {
  interface Listener {
    void event(String event, JSONObject value);
  }

  private final Context context;
  private final ContentResolver resolver;
  private final Store store;
  private final File dataRoot;
  private long storageMeasuredAt;
  private JSONObject storage = new JSONObject();
  private String resolutionKey = "";
  private JSONObject savedResolution;
  private final File cache;
  private final Object cacheLock = new Object();
  private volatile boolean accessible, scanning;
  private volatile String accessMessage = "";
  private volatile JSONObject progress = new JSONObject();
  private volatile CancellationSignal cancellation;
  private volatile long cacheBytes;
  private long lastProgressAt;
  private final ConcurrentHashMap<String, ReadGroup> reads = new ConcurrentHashMap<>();
  private final Semaphore readSlots = new Semaphore(4, true);
  private final Semaphore largeReadSlots = new Semaphore(1, true);
  private final Set<InputStream> openStreams = ConcurrentHashMap.newKeySet();
  private volatile boolean acceptingReads = true;
  private final AtomicLong sourceReads = new AtomicLong(),
      joinedReads = new AtomicLong(),
      readNanos = new AtomicLong(),
      sourceBytes = new AtomicLong();

  LocalImages(Context context, Store store) {
    this.context = context.getApplicationContext();
    this.resolver = context.getContentResolver();
    this.store = store;
    dataRoot = context.getDataDir();
    cache = new File(context.getCacheDir(), "local-thumbnails");
    cacheBytes = size(cache);
    try {
      clearCache();
    } catch (IOException error) {
      store.log("image_cache_migration", "INCOMPLETE");
    }
  }

  static long size(File folder) {
    long bytes = 0;
    File[] files = folder.listFiles();
    if (files != null) for (File file : files) bytes += file.length();
    return bytes;
  }

  boolean scanning() {
    return scanning;
  }

  void checkAccess() {
    String value;
    synchronized (store) {
      value = store.settings.optString("treeUri");
    }
    accessible = false;
    accessMessage = "";
    if (value.isEmpty()) return;
    try {
      Uri tree = Uri.parse(value);
      boolean permitted = false;
      for (UriPermission permission : resolver.getPersistedUriPermissions())
        if (permission.getUri().equals(tree) && permission.isReadPermission()) permitted = true;
      if (!permitted)
        throw new SecurityException(
            "Folder permission was lost. Select the thumbnail folder again.");
      Uri document =
          DocumentsContract.buildDocumentUriUsingTree(
              tree,
              store.settings.optString("directoryId", DocumentsContract.getTreeDocumentId(tree)));
      try (Cursor cursor =
          resolver.query(
              document,
              new String[] {DocumentsContract.Document.COLUMN_MIME_TYPE},
              null,
              null,
              null)) {
        if (cursor == null
            || !cursor.moveToFirst()
            || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0)))
          throw new IOException("Folder was moved, removed, or is unavailable. Select it again.");
      }
      accessible = true;
    } catch (Exception error) {
      accessMessage =
          error instanceof SecurityException
              ? "Folder permission was lost. Select the thumbnail folder again."
              : "Thumbnail folder is unavailable. Check its location or select it again.";
      store.log("folder_access", error.getClass().getSimpleName());
    }
  }

  void select(Uri tree, int flags) throws Exception {
    if (scanning) throw new IOException("Wait for the current scan to finish.");
    int read = flags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
    if (read == 0) throw new SecurityException("Read permission was not granted.");
    resolver.takePersistableUriPermission(tree, read);
    String directoryId = DocumentsContract.getTreeDocumentId(tree), label = directoryId, old;
    Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, directoryId);
    try (Cursor cursor =
        resolver.query(
            document,
            new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
            null,
            null,
            null)) {
      if (cursor == null || !cursor.moveToFirst())
        throw new IOException("Selected folder is unavailable.");
      label = cursor.getString(0);
    }
    // Some system pickers hide dot folders. A selected parent may grant its .thumbnails child.
    if (!".thumbnails".equalsIgnoreCase(label)) {
      Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId);
      try (Cursor cursor =
          resolver.query(
              children,
              new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
              },
              null,
              null,
              null)) {
        if (cursor != null)
          while (cursor.moveToNext())
            if (".thumbnails".equalsIgnoreCase(cursor.getString(1))
                && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
              directoryId = cursor.getString(0);
              label = cursor.getString(1);
              break;
            }
      }
    }
    label += " · " + directoryId;
    synchronized (store) {
      old = store.settings.optString("treeUri");
      store
          .settings
          .put("treeUri", tree.toString())
          .put("directoryId", directoryId)
          .put("folderLabel", label)
          .put("scanState", "not_scanned");
      store.saveSettings();
      store.replaceIndex(new JSONObject());
    }
    clearCache();
    if (!old.isEmpty() && !old.equals(tree.toString()))
      try {
        int release = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        for (UriPermission permission : resolver.getPersistedUriPermissions())
          if (permission.getUri().toString().equals(old) && permission.isWritePermission())
            release |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        resolver.releasePersistableUriPermission(Uri.parse(old), release);
      } catch (Exception ignored) {
      }
    checkAccess();
    if (!accessible) throw new IOException(accessMessage);
    store.log("folder_select", "success");
  }

  void cancelScan() {
    CancellationSignal signal = cancellation;
    if (signal != null) signal.cancel();
  }

  void scan(Listener listener) {
    scan(listener, false);
  }

  // Retained for existing callers. Rescan is always read-only, even with write permission.
  void scan(Listener listener, boolean cleanupRequested) {
    scan(listener, cleanupRequested, new CancellationSignal());
  }

  void scan(Listener listener, boolean cleanupRequested, CancellationSignal request) {
    synchronized (this) {
      if (scanning) return;
      scanning = true;
    }
    cancellation = request;
    String treeValue;
    synchronized (store) {
      treeValue = store.settings.optString("treeUri");
    }
    long started = android.os.SystemClock.elapsedRealtime();
    lastProgressAt = 0;
    try (ThumbnailScanner scanner = new ThumbnailScanner(context, store.root)) {
      long jsonBefore = store.writeMetrics().optLong("snapshotNanos");
      checkAccess();
      if (!accessible)
        throw new IOException(
            accessMessage.isEmpty() ? "Select a thumbnail folder first." : accessMessage);
      String directoryId;
      JSONObject previousProfiles;
      synchronized (store) {
        store.settings.put("scanState", "scanning");
        store.saveSettings();
        directoryId =
            store.settings.optString(
                "directoryId", DocumentsContract.getTreeDocumentId(Uri.parse(treeValue)));
        previousProfiles =
            treeValue.equals(store.index.optString("treeUri"))
                ? store.index.optJSONObject("profiles")
                : null;
      }
      ThumbnailPolicy.Catalog catalog;
      try {
        catalog =
            scanner.scan(
                Uri.parse(treeValue),
                directoryId,
                previousProfiles,
                cancellation,
                (processed, total) -> publish(listener, "scanning", processed, total, started));
      } finally {
        progress.put("io", scanner.metrics());
      }
      cancellation.throwIfCanceled();
      JSONObject profiles = new JSONObject();
      for (Map.Entry<String, ThumbnailPolicy.Candidate> entry : catalog.best.entrySet()) {
        ThumbnailPolicy.Candidate c = entry.getValue();
        profiles.put(
            entry.getKey(),
            new JSONObject()
                .put("uri", c.uri)
                .put("name", c.name)
                .put("readable", c.readable)
                .put("width", c.width)
                .put("height", c.height)
                .put("size", c.size)
                .put("modified", c.modified));
      }
      long now = System.currentTimeMillis(), revision;
      synchronized (store) {
        boolean unchanged =
            treeValue.equals(store.index.optString("treeUri"))
                && sameProfileCatalog(previousProfiles, profiles)
                && catalog.unreadable == store.index.optInt("unreadable")
                && catalog.duplicates == store.index.optInt("duplicates")
                && catalog.files == store.index.optInt("files")
                && catalog.bytes == store.index.optLong("bytes")
                && catalog.unknownSizes == store.index.optInt("unknownSizes");
        revision =
            unchanged
                ? store.index.optLong("revision")
                : Math.max(now, store.index.optLong("revision") + 1);
      }
      JSONObject cleanup =
          new JSONObject()
              .put("deleted", 0)
              .put("preserved", 0)
              .put("errors", 0)
              .put("state", "Read-only indexing — source files unchanged");
      JSONObject next =
          new JSONObject()
              .put("schema", 1)
              .put("treeUri", treeValue)
              .put("revision", revision)
              .put("scannedAt", now)
              .put("profiles", profiles)
              .put("unreadable", catalog.unreadable)
              .put("duplicates", catalog.duplicates)
              .put("files", catalog.files)
              .put("bytes", catalog.bytes)
              .put("unknownSizes", catalog.unknownSizes)
              .put("cleanup", cleanup)
              .put(
                  "resolutionAudit",
                  ResolutionAudit.result(
                      catalog.nonTargetResolution, catalog.unknownResolution, true));
      synchronized (store) {
        if (!treeValue.equals(store.settings.optString("treeUri"))
            || !directoryId.equals(store.settings.optString("directoryId")))
          throw new IOException("Folder changed during scan.");
        // Atomic public snapshot first. A crash before complete() only retains extra private
        // checkpoints.
        store.replaceIndex(next);
        scanner.index.complete();
        store.settings.put("scanState", "completed");
        store.saveSettings();
      }
      store.log("thumbnail_scan", "success");
      publish(listener, "completed", scanner.total, scanner.total, started);
      progress.put(
          "io",
          scanner
              .metrics()
              .put("jsonWriteNanos", store.writeMetrics().optLong("snapshotNanos") - jsonBefore));
    } catch (Exception | OutOfMemoryError error) {
      String state =
          error instanceof InterruptedException
                  || cancellation.isCanceled()
                  || error instanceof OutOfMemoryError
              ? "interrupted"
              : "failed";
      try {
        synchronized (store) {
          store.settings.put("scanState", state);
          store.saveSettings();
        }
      } catch (Exception ignored) {
      }
      if (error instanceof SecurityException) {
        accessible = false;
        accessMessage = "Folder permission was lost. Select the folder again.";
      }
      try {
        String message =
            error instanceof OutOfMemoryError
                ? "Scan paused: low memory. Saved checkpoints and the last completed index are"
                    + " preserved."
                : "interrupted".equals(state)
                    ? "Scan interrupted. Saved checkpoints and the last completed index are"
                        + " preserved."
                    : "Scan failed. Saved checkpoints are preserved; retry to resume.";
        JSONObject value =
            new JSONObject()
                .put("state", state)
                .put("message", message)
                .put("io", progress.optJSONObject("io"));
        progress = value;
        listener.event("scan-progress", value);
      } catch (Exception ignored) {
      }
      store.log("thumbnail_scan", state);
      store.log("thumbnail_scan_error", error.getClass().getSimpleName());
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
    } finally {
      scanning = false;
      try {
        listener.event("status", snapshot(true));
      } catch (Exception ignored) {
      }
    }
  }

  private static boolean sameProfileCatalog(JSONObject oldProfiles, JSONObject next) {
    if (oldProfiles == null || oldProfiles.length() != next.length()) return false;
    for (Iterator<String> it = next.keys(); it.hasNext(); ) {
      String key = it.next();
      JSONObject old = oldProfiles.optJSONObject(key), current = next.optJSONObject(key);
      if (old == null || current == null) return false;
      for (String field :
          new String[] {"uri", "name", "readable", "width", "height", "size", "modified"})
        if (!Objects.equals(old.opt(field), current.opt(field))) return false;
    }
    return true;
  }

  private void publish(Listener listener, String state, int processed, int total, long started)
      throws JSONException {
    long now = android.os.SystemClock.elapsedRealtime();
    if ("scanning".equals(state)
        && processed > 0
        && processed < total
        && now - lastProgressAt < 250) return;
    lastProgressAt = now;
    JSONObject value =
        new JSONObject()
            .put("state", state)
            .put("processed", processed)
            .put("total", total)
            .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
    progress = value;
    listener.event("scan-progress", value);
  }

  private static long treeBytes(File file) {
    if (file.isFile()) return file.length();
    long total = 0;
    File[] entries = file.listFiles();
    if (entries != null) for (File entry : entries) total += treeBytes(entry);
    return total;
  }

  JSONObject storageAudit() throws JSONException {
    JSONObject result = new JSONObject();
    for (String name :
        new String[] {"files", "cache", "databases", "shared_prefs", "app_webview", "code_cache"})
      result.put(name, treeBytes(new File(dataRoot, name)));
    long checkpointBytes = 0;
    for (String suffix : new String[] {"", "-wal", "-shm"})
      checkpointBytes += new File(store.root, "thumbnail-scan.db" + suffix).length();
    return result
        .put("thumbnailIndex", new File(store.root, "thumbnail-index.json").length())
        .put("thumbnailCheckpoint", checkpointBytes)
        .put("derivedImages", size(cache))
        .put("nativeBitmapCache", 0);
  }

  JSONObject snapshot(boolean includeProfiles) throws Exception {
    long now = android.os.SystemClock.elapsedRealtime();
    if (storageMeasuredAt == 0 || now - storageMeasuredAt > 30000) {
      storage = storageAudit();
      storageMeasuredAt = now;
    }
    synchronized (store) {
      JSONObject source = store.index.optJSONObject("profiles");
      if (source == null) source = new JSONObject();
      boolean usable =
          accessible
              && store
                  .settings
                  .optString("treeUri")
                  .equals(store.index.optString("treeUri", "invalid"));
      Set<String> users = store.users();
      int matched = 0;
      JSONObject profiles = new JSONObject();
      for (String user : users) {
        JSONObject image = source.optJSONObject(user);
        if (usable && image != null && image.optBoolean("readable")) {
          matched++;
          if (includeProfiles) profiles.put(user, profileSnapshot(image));
        }
      }
      JSONObject out =
          new JSONObject()
              .put("total", store.total())
              .put("unique", users.size())
              .put("matched", matched)
              .put("missing", users.size() - matched)
              .put("unreadable", store.index.optInt("unreadable"))
              .put("duplicates", store.index.optInt("duplicates"))
              .put("folderBytes", store.index.optLong("bytes"))
              .put("unknownSizes", store.index.optInt("unknownSizes"))
              .put("lastScan", store.index.optLong("scannedAt"))
              .put("folder", store.settings.optString("folderLabel", "Not selected"))
              .put(
                  "folderStatus",
                  accessible
                      ? "ready"
                      : store.settings.optString("treeUri").isEmpty()
                          ? "not_selected"
                          : "unavailable")
              .put("folderMessage", accessMessage)
              .put(
                  "scanState",
                  scanning ? "scanning" : store.settings.optString("scanState", "idle"))
              .put("scan", progress)
              .put("revision", store.index.optLong("revision"))
              .put("cacheBytes", cacheBytes)
              .put("cleanup", store.index.optJSONObject("cleanup"));
      out.put("storage", storage)
          .put("resolution", resolution())
          .put("indexWrites", store.writeMetrics());
      out.put(
          "imageIO",
          new JSONObject()
              .put("sourceReads", sourceReads.get())
              .put("coalescedReads", joinedReads.get())
              .put("readNanos", readNanos.get())
              .put("sourceBytes", sourceBytes.get())
              .put("inflight", reads.size())
              .put("nativeBitmapCacheBytes", 0)
              .put("derivedDiskCacheBytes", cacheBytes));
      if (includeProfiles) out.put("profiles", profiles);
      return out;
    }
  }

  private JSONObject profileSnapshot(JSONObject image) throws JSONException {
    return new JSONObject()
        .put("file", true)
        .put("width", image.optInt("width"))
        .put("height", image.optInt("height"))
        .put(
            "version",
            fingerprint(image)
                + ":"
                + store.sourceEpoch
                + ":"
                + store.externalRevision
                + (image.optLong("modified") > 0 && image.optLong("size", -1) >= 0
                    ? ""
                    : ":" + store.index.optLong("scannedAt")));
  }

  String scopeRevision() {
    synchronized (store) {
      return Store.key(
          store.settings.optString("treeUri")
              + "\n"
              + store.settings.optString("directoryId")
              + "\n"
              + accessible
              + "\n"
              + store.collectionGeneration
              + "\n"
              + store.index.optLong("scannedAt")
              + "\n"
              + store.externalRevision);
    }
  }

  long revision() {
    synchronized (store) {
      return store.index.optLong("revision");
    }
  }

  JSONObject snapshotChanges(long previous, String previousScope, boolean forceFull)
      throws Exception {
    synchronized (store) {
      String scope = scopeRevision();
      Set<String> changed = store.changesSince(previous);
      boolean full = forceFull || !scope.equals(previousScope) || changed == null;
      JSONObject out = snapshot(full).put("scopeRevision", scope);
      if (!full && !changed.isEmpty()) {
        JSONObject changes = new JSONObject(), source = store.index.optJSONObject("profiles");
        for (String user : changed) {
          JSONObject image = source == null ? null : source.optJSONObject(user);
          changes.put(
              user,
              image != null && image.optBoolean("readable")
                  ? profileSnapshot(image)
                  : new JSONObject().put("file", false));
        }
        out.put("profileChanges", changes);
      }
      return out;
    }
  }

  String missingUsernames() throws JSONException {
    synchronized (store) {
      boolean usable =
          accessible
              && store
                  .settings
                  .optString("treeUri")
                  .equals(store.index.optString("treeUri", "invalid"));
      return MissingProfiles.text(store.users(), store.index.optJSONObject("profiles"), usable);
    }
  }

  private JSONObject resolution() throws JSONException {
    JSONObject audit = store.index.optJSONObject("resolutionAudit");
    if (audit != null) return ResolutionAudit.snapshot(store.index);
    String scope =
        store.settings.optString("treeUri") + "\n" + store.settings.optString("directoryId");
    String key =
        scope
            + "\n"
            + store.index.optLong("scannedAt")
            + "\n"
            + store.settings.optString("scanState");
    if (!key.equals(resolutionKey) || savedResolution == null) {
      savedResolution = null;
      // Reuse the existing completed all-file checkpoint on older installations.
      // This is a private aggregate query, never a SAF listing or an image open.
      if (new File(store.root, "thumbnail-scan.db").isFile())
        try (ThumbnailScanIndex checkpoints = new ThumbnailScanIndex(context, store.root)) {
          savedResolution = checkpoints.resolution(scope);
        } catch (android.database.sqlite.SQLiteException unavailable) {
          store.log("resolution_audit", "CHECKPOINT_UNAVAILABLE");
        }
      if (savedResolution == null) savedResolution = ResolutionAudit.snapshot(store.index);
      resolutionKey = key;
    }
    return new JSONObject(savedResolution.toString());
  }

  private static String fingerprint(JSONObject image) {
    return image.optString("modified")
        + ":"
        + image.optString("size")
        + ":"
        + image.optString("uri").hashCode();
  }

  String mime(String user) {
    synchronized (store) {
      JSONObject profiles = store.index.optJSONObject("profiles"),
          image = profiles == null ? null : profiles.optJSONObject(user);
      String name = image == null ? "" : image.optString("name").toLowerCase(Locale.ROOT);
      return name.endsWith(".png")
          ? "image/png"
          : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg" : "image/webp";
    }
  }

  private final class ReadGroup {
    final CompletableFuture<SourceBytes> result = new CompletableFuture<>();
    final java.util.concurrent.atomic.AtomicInteger consumers =
        new java.util.concurrent.atomic.AtomicInteger(1);
    final CancellationSignal cancel = new CancellationSignal();
    volatile boolean large;

    void release() {
      if (consumers.decrementAndGet() == 0) {
        result.obtrudeValue(SourceBytes.EMPTY);
        if (large) largeReadSlots.release();
      }
    }
  }

  InputStream openImage(String user) {
    if (!acceptingReads) return new ByteArrayInputStream(new byte[0]);
    JSONObject image;
    synchronized (store) {
      if (!accessible
          || !store
              .settings
              .optString("treeUri")
              .equals(store.index.optString("treeUri", "invalid")))
        return new ByteArrayInputStream(new byte[0]);
      JSONObject profiles = store.index.optJSONObject("profiles"),
          value = profiles == null ? null : profiles.optJSONObject(user);
      if (value == null || !value.optBoolean("readable"))
        return new ByteArrayInputStream(new byte[0]);
      try {
        image = new JSONObject(value.toString());
      } catch (JSONException error) {
        return new ByteArrayInputStream(new byte[0]);
      }
    }
    String key = user + "|" + fingerprint(image);
    java.util.concurrent.atomic.AtomicBoolean owner =
        new java.util.concurrent.atomic.AtomicBoolean();
    ReadGroup group =
        reads.compute(
            key,
            (k, existing) -> {
              if (existing != null) {
                existing.consumers.incrementAndGet();
                joinedReads.incrementAndGet();
                return existing;
              }
              owner.set(true);
              return new ReadGroup();
            });
    if (owner.get()) {
      boolean acquired = false;
      long started = System.nanoTime();
      SourceBytes data = SourceBytes.EMPTY;
      try {
        if (image.optLong("size", -1) < 0 || image.optLong("size") > 1024 * 1024) {
          if (!largeReadSlots.tryAcquire(30, TimeUnit.SECONDS))
            throw new IOException("Local read capacity");
          group.large = true;
        }
        acquired = readSlots.tryAcquire(10, TimeUnit.SECONDS);
        if (!acquired) throw new IOException("Local read capacity");
        sourceReads.incrementAndGet();
        try (InputStream input =
            ImageCodec.open(resolver, Uri.parse(image.optString("uri")), group.cancel)) {
          data = SourceBytes.read(input);
          sourceBytes.addAndGet(data.length);
        }
      } catch (Exception | OutOfMemoryError error) {
        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        failed(user, image, error);
      } finally {
        if (acquired) readSlots.release();
        readNanos.addAndGet(System.nanoTime() - started);
      }
      group.result.complete(data);
      reads.remove(key, group);
    }
    try {
      SourceBytes data = group.result.get(30, TimeUnit.SECONDS);
      if (!acceptingReads) {
        group.release();
        return new ByteArrayInputStream(new byte[0]);
      }
      InputStream stream =
          new FilterInputStream(data.open()) {
            final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public int read() throws IOException {
              if (closed.get()) return -1;
              int n = super.read();
              if (n < 0) close();
              return n;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
              if (closed.get()) return -1;
              int n = in.read(bytes, offset, length);
              if (n < 0) close();
              return n;
            }

            @Override
            public void close() throws IOException {
              if (!closed.compareAndSet(false, true)) return;
              try {
                super.close();
              } finally {
                openStreams.remove(this);
                group.release();
              }
            }
          };
      openStreams.add(stream);
      return stream;
    } catch (Exception | OutOfMemoryError error) {
      group.release();
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      return new ByteArrayInputStream(new byte[0]);
    }
  }

  void closeStreams() {
    closeStreams(true);
  }

  private void closeStreams(boolean permanent) {
    if (permanent) acceptingReads = false;
    for (ReadGroup group : reads.values()) group.cancel.cancel();
    for (InputStream stream : openStreams)
      try {
        stream.close();
      } catch (IOException error) {
        store.log("image_close", "IO_ERROR");
      }
  }

  byte[] image(String user, boolean full) {
    if (full) {
      JSONObject image;
      synchronized (store) {
        JSONObject profiles = store.index.optJSONObject("profiles");
        image = profiles == null ? null : profiles.optJSONObject(user);
      }
      if (image == null) return new byte[0];
      synchronized (cacheLock) {
        try {
          return ImageCodec.render(resolver, Uri.parse(image.optString("uri")), true);
        } catch (Exception | OutOfMemoryError error) {
          return failed(user, image, error);
        }
      }
    }
    // Compatibility adapter for existing native callers; WebView uses openImage directly.
    try (InputStream input = openImage(user);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int n;
      while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
      return output.toByteArray();
    } catch (IOException error) {
      return new byte[0];
    }
  }

  private byte[] failed(String user, JSONObject expected, Throwable error) {
    if (error instanceof OutOfMemoryError) {
      com.instagram.insfav.fetcher.Fetcher.memoryFailure();
      closeStreams(false);
    }
    store.log("image_read", error.getClass().getSimpleName());
    // Temporary provider failures do not permanently condemn an indexed image.
    try {
      synchronized (store) {
        if (error instanceof SecurityException) {
          accessible = false;
          accessMessage = "Folder permission was lost. Select the folder again.";
        } else if (error instanceof FileNotFoundException) {
          JSONObject profiles = store.index.optJSONObject("profiles"),
              current = profiles == null ? null : profiles.optJSONObject(user);
          if (current != null
              && current.optBoolean("readable")
              && fingerprint(current).equals(fingerprint(expected))) {
            current.put("readable", false);
            store.index.put("unreadable", store.index.optInt("unreadable") + 1);
            store.replaceIndex(store.index);
          }
        }
      }
    } catch (Exception ignored) {
    }
    return new byte[0];
  }

  void clearCache() throws IOException {
    synchronized (cacheLock) {
      File[] files = cache.listFiles();
      boolean failed = false;
      if (files != null)
        for (File file : files)
          if (file.isFile() && file.getName().matches("[a-f0-9]{64}\\.webp(?:\\.bak|\\.new)?"))
            if (!file.delete()) failed = true;
      cacheBytes = size(cache);
      if (failed) throw new IOException("Legacy image cache could not be fully cleared.");
    }
  }

  void clearLegacyCache() {
    for (File legacy :
        new File[] {
          new File(store.root, "thumbnails"), new File(cache.getParentFile(), "full-resolution")
        }) {
      File[] old = legacy.listFiles();
      if (old != null) for (File file : old) if (file.isFile()) file.delete();
    }
  }

  void clearIndex() throws Exception {
    if (scanning) throw new IOException("Wait for the scan to finish.");
    try (ThumbnailScanIndex checkpoints = new ThumbnailScanIndex(context, store.root)) {
      checkpoints.clear();
    }
    synchronized (store) {
      store.replaceIndex(new JSONObject());
      store.settings.put("scanState", "not_scanned");
      store.saveSettings();
    }
    clearCache();
    store.log("thumbnail_index_clear", "success");
  }
}
