package com.instagram.insfav;

import android.content.Context;
import android.util.AtomicFile;
import com.instagram.insfav.fetcher.IndexJournal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Keeps the original collection document independent from replaceable image metadata. */
@android.annotation.SuppressLint("StaticFieldLeak") // Singleton owns only the application context.
final class Store {
  private static Store instance;

  static synchronized Store get(Context context) {
    if (instance == null) instance = new Store(context.getApplicationContext());
    return instance;
  }

  final File root;
  JSONObject index, settings;
  private LinkedHashSet<String> cachedUsers;
  private int total;
  private final File diagnostic;
  private final Context context;
  private static final ScheduledExecutorService snapshots =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "insfav-index-snapshot");
            t.setDaemon(true);
            return t;
          });
  private ScheduledFuture<?> pendingSnapshot;
  private int dirtyChanges;
  private long snapshotWrites, snapshotBytes, snapshotNanos, deltaWrites, recoveredDeltas;
  final long sourceEpoch = System.currentTimeMillis();
  long externalRevision, collectionGeneration, deltaFloor;
  private final NavigableMap<Long, String> profileChanges = new TreeMap<>();

  Store(Context context) {
    this.context = context.getApplicationContext();
    root = context.getFilesDir();
    diagnostic = new File(root, "diagnostics.txt");
    index = readObject("thumbnail-index.json");
    settings = readObject("image-settings.json");
    deltaFloor = index.optLong("revision");
    // Migrate only obsolete retrieval state, never the original collection document.
    for (String name : new String[] {"queue.json", "network.json", "profiles.json"})
      new AtomicFile(new File(root, name)).delete();
    if ("scanning".equals(settings.optString("scanState"))) {
      try {
        settings.put("scanState", "interrupted");
        saveSettings();
      } catch (Exception ignored) {
      }
    }
    try (IndexJournal journal = new IndexJournal(context)) {
      for (JSONObject delta : journal.pending(scope())) {
        if (delta.getLong("revision") > index.optLong("revision")) {
          applyProfile(
              delta.getString("user"),
              delta.getJSONObject("profile"),
              settings.optString("treeUri"),
              delta.getLong("revision"));
          recoveredDeltas++;
          dirtyChanges++;
        }
      }
      if (dirtyChanges > 0) flushIndex();
      else journal.acknowledge(scope(), index.optLong("revision"));
      deltaFloor = index.optLong("revision");
      profileChanges.clear();
    } catch (Exception error) {
      log("index_recovery", "FAILED");
      throw new IllegalStateException("INDEX_RECOVERY_FAILED");
    }
  }

  static String normalize(String value) {
    return ThumbnailPolicy.normalize(value);
  }

  static String key(String value) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : bytes)
        out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
      return out.toString();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  synchronized String original() {
    try {
      return new String(
          new AtomicFile(new File(root, "document.json")).readFully(), StandardCharsets.UTF_8);
    } catch (FileNotFoundException error) {
      return "";
    } catch (IOException error) {
      log("document_read", "IO_ERROR");
      return "";
    }
  }

  static JSONArray collections(String raw) throws JSONException {
    Object root = new JSONTokener(raw).nextValue();
    Object value = root instanceof JSONArray ? root : ((JSONObject) root).get("favorite_page_pref");
    if (value instanceof String) value = new JSONTokener((String) value).nextValue();
    if (!(value instanceof JSONArray)) throw new JSONException("Invalid collection array");
    JSONArray collections = (JSONArray) value;
    for (int i = 0; i < collections.length(); i++) {
      JSONObject collection = collections.getJSONObject(i);
      if (!(collection.get("name") instanceof String))
        throw new JSONException("Invalid collection name");
      JSONArray items = collection.getJSONArray("items");
      for (int j = 0; j < items.length(); j++) {
        JSONObject item = items.getJSONObject(j);
        if (!(item.get("pageId") instanceof String)
            || item.has("name") && !(item.get("name") instanceof String))
          throw new JSONException("Invalid account fields");
      }
    }
    return collections;
  }

  synchronized void importDocument(String raw) throws Exception {
    collections(raw);
    write("document.json", raw);
    cachedUsers = null;
    collectionGeneration++;
    log("import", "success");
  }

  synchronized Set<String> users() throws JSONException {
    if (cachedUsers == null) {
      LinkedHashSet<String> next = new LinkedHashSet<>();
      int count = 0;
      String raw = original();
      if (!raw.isEmpty()) {
        JSONArray array = collections(raw);
        for (int i = 0; i < array.length(); i++) {
          JSONArray items = array.getJSONObject(i).getJSONArray("items");
          count += items.length();
          for (int j = 0; j < items.length(); j++) {
            String user = normalize(items.getJSONObject(j).getString("pageId"));
            if (!user.isEmpty()) next.add(user);
          }
        }
      }
      cachedUsers = next;
      total = count;
    }
    return Collections.unmodifiableSet(cachedUsers);
  }

  /** Fresh validation only: never use a stale cached user set for destructive cleanup. */
  synchronized Set<String> cleanupUsers() {
    try {
      String raw = original();
      if (raw.isEmpty()) return null;
      JSONTokener rootToken = new JSONTokener(raw);
      rootToken.nextValue();
      if (rootToken.nextClean() != 0) return null;
      Object root = new JSONTokener(raw).nextValue();
      if (root instanceof JSONObject) {
        Object nested = ((JSONObject) root).get("favorite_page_pref");
        if (nested instanceof String) {
          JSONTokener token = new JSONTokener((String) nested);
          token.nextValue();
          if (token.nextClean() != 0) return null;
        }
      }
      JSONArray data = collections(raw);
      Set<String> users = new HashSet<>();
      for (int i = 0; i < data.length(); i++) {
        JSONArray items = data.getJSONObject(i).getJSONArray("items");
        for (int n = 0; n < items.length(); n++) {
          String user = normalize(items.getJSONObject(n).getString("pageId"));
          if (!user.matches("[a-z0-9._]{1,30}")) return null;
          users.add(user);
        }
      }
      return users.isEmpty() ? null : users;
    } catch (Exception error) {
      log("thumbnail_cleanup", "INVALID_FAVORITES_PRESERVED");
      return null;
    }
  }

  synchronized int total() throws JSONException {
    if (cachedUsers == null) users();
    return total;
  }

  synchronized boolean hasDocument() {
    File document = new File(root, "document.json");
    return document.isFile() && document.length() > 0;
  }

  synchronized void clearDocument() {
    new AtomicFile(new File(root, "document.json")).delete();
    cachedUsers = null;
    collectionGeneration++;
    log("clear_collections", "success");
  }

  synchronized JSONObject readObject(String name) {
    try {
      return new JSONObject(
          new String(new AtomicFile(new File(root, name)).readFully(), StandardCharsets.UTF_8));
    } catch (Exception error) {
      return new JSONObject();
    }
  }

  synchronized void write(String name, String value) throws IOException {
    writeBytes(name, value.getBytes(StandardCharsets.UTF_8));
  }

  private void writeBytes(String name, byte[] bytes) throws IOException {
    AtomicFile file = new AtomicFile(new File(root, name));
    FileOutputStream output = null;
    try {
      output = file.startWrite();
      output.write(bytes);
      file.finishWrite(output);
    } catch (IOException | RuntimeException | Error error) {
      if (output != null) file.failWrite(output);
      throw error;
    }
  }

  synchronized void saveSettings() throws IOException {
    write("image-settings.json", settings.toString());
  }

  private String scope() {
    return settings.optString("treeUri") + "\n" + settings.optString("directoryId");
  }

  private void writeIndex(JSONObject next) throws IOException {
    long began = System.nanoTime();
    byte[] bytes = next.toString().getBytes(StandardCharsets.UTF_8);
    writeBytes("thumbnail-index.json", bytes);
    snapshotNanos += System.nanoTime() - began;
    snapshotWrites++;
    snapshotBytes += bytes.length;
  }

  synchronized void replaceIndex(JSONObject next) throws IOException {
    if (next == index)
      try {
        next.put("revision", Math.max(System.currentTimeMillis(), index.optLong("revision") + 1));
      } catch (JSONException error) {
        throw new IOException("INDEX_REVISION_FAILED", error);
      }
    writeIndex(next);
    index = next;
    profileChanges.clear();
    deltaFloor = next.optLong("revision");
    try (IndexJournal journal = new IndexJournal(context)) {
      journal.clear();
    }
    dirtyChanges = 0;
    if (pendingSnapshot != null) {
      pendingSnapshot.cancel(false);
      pendingSnapshot = null;
    }
  }

  synchronized void updateProfile(String user, JSONObject profile, String tree) throws Exception {
    if (!tree.equals(settings.optString("treeUri"))) return;
    long revision = Math.max(System.currentTimeMillis(), index.optLong("revision") + 1);
    // Persist before acknowledging the image or updating the in-memory public catalog.
    try (IndexJournal journal = new IndexJournal(context)) {
      journal.record(scope(), user, profile, revision);
    }
    deltaWrites++;
    applyProfile(user, profile, tree, revision);
    dirtyChanges++;
    profileChanges.put(revision, user);
    if (profileChanges.size() > 256) deltaFloor = profileChanges.pollFirstEntry().getKey();
    if (dirtyChanges >= 64) flushIndex();
    else if (pendingSnapshot == null)
      pendingSnapshot =
          snapshots.schedule(
              () -> {
                synchronized (Store.this) {
                  pendingSnapshot = null;
                  try {
                    flushIndex();
                  } catch (Exception error) {
                    log("index_snapshot", "FAILED");
                    com.instagram.insfav.fetcher.Fetcher.indexPersistenceFailure();
                  }
                }
              },
              60,
              TimeUnit.SECONDS);
  }

  private void applyProfile(String user, JSONObject profile, String tree, long revision)
      throws JSONException {
    JSONObject profiles = index.optJSONObject("profiles");
    if (profiles == null) {
      profiles = new JSONObject();
      index.put("profiles", profiles);
    }
    JSONObject old = profiles.optJSONObject(user);
    boolean added = old == null;
    boolean replacing = old != null && profile.optString("name").equals(old.optString("name"));
    long oldSize = replacing ? Math.max(0, old.optLong("size")) : 0;
    if (!added && !replacing) index.put("duplicates", index.optInt("duplicates") + 1);
    if (replacing && !old.optBoolean("readable") && profile.optBoolean("readable"))
      index.put("unreadable", Math.max(0, index.optInt("unreadable") - 1));
    profiles.put(user, new JSONObject(profile.toString()));
    index
        .put("treeUri", tree)
        .put("revision", revision)
        .put(
            "bytes",
            Math.max(0, index.optLong("bytes") - oldSize + Math.max(0, profile.optLong("size"))))
        .put("files", index.optInt("files") + (added || !replacing ? 1 : 0));
  }

  synchronized void flushIndex() throws Exception {
    if (dirtyChanges == 0) return;
    long revision = index.optLong("revision");
    writeIndex(index);
    try (IndexJournal journal = new IndexJournal(context)) {
      journal.acknowledge(scope(), revision);
    }
    dirtyChanges = 0;
    if (pendingSnapshot != null) {
      pendingSnapshot.cancel(false);
      pendingSnapshot = null;
    }
  }

  synchronized JSONObject writeMetrics() throws JSONException {
    return new JSONObject()
        .put("snapshotWrites", snapshotWrites)
        .put("snapshotSerializedBytes", snapshotBytes)
        .put("snapshotNanos", snapshotNanos)
        .put("deltaWrites", deltaWrites)
        .put("pendingChanges", dirtyChanges)
        .put("recoveredDeltas", recoveredDeltas);
  }

  synchronized Set<String> changesSince(long revision) {
    if (revision < deltaFloor || revision > index.optLong("revision")) return null;
    return new LinkedHashSet<>(profileChanges.tailMap(revision, false).values());
  }

  synchronized void log(String event, String code) {
    try {
      if (diagnostic.length() > 256 * 1024) {
        File old = new File(root, "diagnostics.1.txt");
        old.delete();
        diagnostic.renameTo(old);
      }
      try (FileOutputStream output = new FileOutputStream(diagnostic, true)) {
        String clean =
            event.replaceAll("[^a-zA-Z0-9_:-]", "") + " " + code.replaceAll("[^a-zA-Z0-9_:-]", "");
        output.write(
            (System.currentTimeMillis() + " " + clean + "\n").getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException ignored) {
    }
  }

  synchronized String logs() {
    StringBuilder out =
        new StringBuilder("INSFAV diagnostics — no account identifiers or folder URIs\n");
    for (String name : new String[] {"diagnostics.1.txt", "diagnostics.txt"}) {
      try {
        out.append(
            new String(
                java.nio.file.Files.readAllBytes(new File(root, name).toPath()),
                StandardCharsets.UTF_8));
      } catch (IOException ignored) {
      }
    }
    return out.toString();
  }
}
