package com.instagram.insfav.fetcher;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/**
 * Public adapter; the independent helper remains untouched. Never sends secrets in status or
 * diagnostics.
 */
// Queue, session, schedule and recovery decisions must be durable before continuing.
@android.annotation.SuppressLint({"ApplySharedPref", "StaticFieldLeak"})
public final class Fetcher {
  public interface Host {
    Collection<String> users() throws Exception;

    String tree();

    String directory();

    long revision();

    boolean hasImage(String user);

    default boolean catalogReadable(String user) {
      return hasImage(user);
    }

    void imageWritten(String user, Uri uri, String tree) throws Exception;

    default void imageWritten(ImageRecord image, String tree) throws Exception {
      imageWritten(image.user, image.uri, tree);
    }

    default boolean catalogContains(ImageRecord image) {
      return true;
    }

    default void sourceChanged() {}

    default long sourceRevision() {
      return 0;
    }

    default void flushImages() throws Exception {}

    default JSONObject catalog() throws Exception {
      return new JSONObject();
    }
  }

  public static final class ImageRecord {
    public final String user, name;
    public final Uri uri;
    public final long size, modified;
    public final int width, height;

    ImageRecord(
        String user, String uri, String name, long size, int width, int height, long modified) {
      if (!Identity.valid(user) || uri.isEmpty() || width <= 0 || height <= 0)
        throw new IllegalArgumentException("OUTPUT_METADATA_INVALID");
      this.user = user;
      this.uri = Uri.parse(uri);
      this.name = name;
      this.size = size;
      this.width = width;
      this.height = height;
      this.modified = modified;
    }

    public JSONObject profile() throws Exception {
      return new JSONObject()
          .put("uri", uri.toString())
          .put("name", name)
          .put("size", size)
          .put("width", width)
          .put("height", height)
          .put("modified", modified)
          .put("readable", true);
    }
  }

  public interface Listener {
    void changed();
    default void changed(boolean critical){changed();}

    void fullReady(String user, String token, int width, int height);

    void fullFailed(String token, String code);
  }

  private static android.database.ContentObserver catalogObserver;
  private static String observerTree = "";
  private static List<String> lastSynced;
  private static final AtomicLong lastPublished = new AtomicLong();
  private static Context context;
  private static Host host;
  private static final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
  private static final AtomicLong generation = new AtomicLong();
  private static volatile String viewerToken = "";
  private static volatile byte[] fullBytes;
  private static volatile String fullMime = "image/jpeg";
  private static CompletableFuture<byte[]> viewing;

  public static void initialize(Context c, Host h) {
    context = c.getApplicationContext();
    host = h;
    // A new process must not replay an old foreground/background start request.
    SessionState.requireManualStart(context);
    lastSynced = null;
    try {
      watchSource();
    } catch (RuntimeException unavailable) {
      new Diagnostics(context).log("INDEX", "OBSERVER_UNAVAILABLE", 0);
    }
    try {
      android.webkit.CookieManager.getInstance().setAcceptCookie(true);
    } catch (RuntimeException | LinkageError ignored) {
    }
  }

  static Host host() {
    if (host == null) throw new IllegalStateException("FETCHER_NOT_READY");
    return host;
  }

  public static void addListener(Listener l) {
    listeners.add(l);
  }

  public static void removeListener(Listener l) {
    listeners.remove(l);
  }

  private static final ScheduledExecutorService updates =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "insfav-status-updates");
            t.setDaemon(true);
            return t;
          });
  private static ScheduledFuture<?> pendingUpdate;

  static void publish() {
    publish(false);
  }

  static synchronized void publish(boolean critical) {
    long now = System.nanoTime(), delay = 1000000000L - (now - lastPublished.get());
    if (critical || delay <= 0) {
      if (pendingUpdate != null) {
        pendingUpdate.cancel(false);
        pendingUpdate = null;
      }
      lastPublished.set(now);
      for (Listener listener : listeners) listener.changed(critical);
    } else if (pendingUpdate == null)
      pendingUpdate =
          updates.schedule(
              () -> {
                synchronized (Fetcher.class) {
                  pendingUpdate = null;
                }
                publish(false);
              },
              delay,
              TimeUnit.NANOSECONDS);
  }

  /** Re-register cheaply on cold launch/resume without cloning the catalog or seeding the queue. */
  public static synchronized void watchSource() {
    String tree = host().tree(), key = tree + "\n" + host().directory();
    if (key.equals(observerTree)) return;
    if (catalogObserver != null) {
      context.getContentResolver().unregisterContentObserver(catalogObserver);
      catalogObserver = null;
    }
    observerTree = "";
    if (!tree.isEmpty()) {
      android.database.ContentObserver next =
          new android.database.ContentObserver(
              new android.os.Handler(android.os.Looper.getMainLooper())) {
            @Override
            public void onChange(boolean self, Uri uri) {
              CompletableFuture.runAsync(() -> FetcherCatalog.observe(context, uri));
            }
          };
      context.getContentResolver().registerContentObserver(Uri.parse(tree), true, next);
      catalogObserver = next;
    }
    observerTree = key;
  }

  public static synchronized void sync() throws Exception {
    if (QueueControl.lock.get())
      throw new IOException("Pause the fetcher before changing collections or folder.");
    SharedPreferences s = SessionState.prefs(context);
    String tree = host().tree(), dir = host().directory();
    List<String> users = new ArrayList<>(host().users());
    if (!users.equals(lastSynced)) {
      try (QueueStore q = new QueueStore(context)) {
        q.sync(users);
      }
      lastSynced = users;
    }
    try {
      watchSource();
    } catch (RuntimeException unavailable) {
      new Diagnostics(context).log("INDEX", "OBSERVER_UNAVAILABLE", 0);
    }
    try (QueueStore q = new QueueStore(context)) {
      FetcherCatalog.seed(q);
    }
    if (!tree.equals(s.getString("tree", "")) || !dir.equals(s.getString("directoryId", "")))
      s.edit()
          .putString("tree", tree)
          .putString("directoryId", dir)
          .putLong("datasetGeneration", s.getLong("datasetGeneration", 0) + 1)
          .putBoolean("armed", false)
          .putBoolean("userPaused", true)
          .commit();
  }

  public static void memoryFailure() {
    if (context == null) return;
    SessionState.prefs(context)
        .edit()
        .putString("blocked", "IMAGE_MEMORY_LIMIT")
        .putString("state", "Paused: memory limit; queue preserved")
        .commit();
    FetchBroker.get(context).cancelAll();
    closeViewer();
    WorkScheduler.cancel(context);
    publish(true);
  }

  public static void indexPersistenceFailure() {
    if (context == null) return;
    SessionState.prefs(context)
        .edit()
        .putString("blocked", "INDEX_SNAPSHOT_FAILED")
        .putString("state", "Paused: index snapshot failed; durable changes preserved")
        .commit();
    FetchBroker.get(context).cancelAll();
    WorkScheduler.cancel(context);
    publish(true);
  }

  public static void memoryPressure() {
    QueueControl.memoryPressure = true;
    FetchBroker.get(context).wakeup();
  }

  public static boolean running() {
    return QueueControl.lock.get();
  }

  public static boolean writable() {
    String tree = host().tree();
    for (UriPermission p : context.getContentResolver().getPersistedUriPermissions())
      if (p.getUri().toString().equals(tree) && p.isWritePermission() && p.isReadPermission())
        return true;
    return false;
  }

  public static void saveCookies(String text) throws Exception {
    if (text.length() > 131072) throw new IOException("Cookie text is too large");
    if (running() || !viewerToken.isEmpty())
      throw new IOException("Pause retrieval and close the image viewer before changing cookies.");
    CookieJar jar = CookieJar.parse(text, System.currentTimeMillis());
    new CookieVault(context).save(jar);
    SessionState.changed(context);
    new Diagnostics(context).log("COOKIE", "SAVED", 0);
    publish();
  }

  public static void removeCookies() {
    QueueControl.pause(context, true);
    closeViewer();
    FetchBroker.get(context).cancelAll();
    new CookieVault(context).remove(); InstagramSession.removeManual();
    SessionState.changed(context);
    publish();
  }

  public static void configure(int min, int max, String start, String end, boolean background)
      throws Exception {
    configure(
        min,
        max,
        start,
        end,
        background,
        SessionState.prefs(context).getInt("concurrentWebViews", 1));
  }

  public static void configure(
      int min, int max, String start, String end, boolean background, int concurrent)
      throws Exception {
    ConcurrencyPolicy.validate(concurrent);
    if (running())
      throw new IOException("Pause retrieval before changing the schedule or worker count.");
    new RunPolicy(min, max, RunPolicy.minute(start), RunPolicy.minute(end));
    SessionState.prefs(context)
        .edit()
        .putInt("minimum", min)
        .putInt("maximum", max)
        .putInt("startMinute", RunPolicy.minute(start))
        .putInt("endMinute", RunPolicy.minute(end))
        .putBoolean("background", background)
        .putInt("concurrentWebViews", concurrent)
        .putLong(
            "nextRequestAt",
            Math.max(
                SessionState.prefs(context).getLong("nextRequestAt", 0),
                System.currentTimeMillis() + min * 1000L))
        .commit();
    WorkScheduler.cancel(context);
    WorkScheduler.recovery(context);
    WorkScheduler.plan(context, false);
    publish();
  }

  public static void action(String action) throws Exception {
    if (action.equals("pause") || action.equals("stop")) {
      QueueControl.pause(context, action.equals("stop"));
      return;
    }
    if (!action.equals("missing")
        && !action.equals("resume")
        && !action.equals("retry_skipped")
        && !action.equals("verify_index")) throw new IOException("Unknown fetcher action");
    if (running()) return;
    RunPolicy policy = RunPolicy.read(SessionState.prefs(context));
    if (!action.equals("verify_index") && !policy.contains(java.time.ZonedDateTime.now())) {
      SessionState.prefs(context).edit().putString("state", "Paused — Outside Schedule").commit();
      publish();
      throw new IOException(
          ScheduleStatus.outsideMessage(
              policy, System.currentTimeMillis(), java.time.ZoneId.systemDefault()));
    }
    if (!action.equals("verify_index")
        && SessionState.prefs(context).getLong("systemNotBefore", 0) > System.currentTimeMillis()) {
      SessionState.prefs(context)
          .edit()
          .putString("state", "Waiting — Android Background Restrictions")
          .commit();
      publish(true);
      throw new IOException(
          "Android background restrictions are active. Try again in the next working window.");
    }
    sync();
    if (!action.equals("verify_index")) InstagramSession.requireReady(context);
    if (!action.equals("verify_index") && !InstagramSession.configured(context))
      throw new IOException("Sign in through Instagram Login or select and save Manual Cookies first.");
    if (host().tree().isEmpty())
      throw new IOException("Select the thumbnail folder in Profile Images first.");
    if (!writable())
      throw new IOException("Grant write access to the selected thumbnail folder first.");
    SharedPreferences s = SessionState.prefs(context);
    if (action.equals("verify_index")) {
      QueueControl.requested = false;
      FetchService.request(context, action);
      return;
    }
    if (!action.equals("verify_index") && System.currentTimeMillis() < s.getLong("retryAt", 0))
      throw new IOException("Retry cooldown is still active.");
    QueueControl.requested = false;
    if (action.equals("missing")) s.edit().putLong("elapsedSeconds", 0).commit();
    s.edit()
        .putBoolean("armed", true)
        .putBoolean("userPaused", false)
        .putString("blocked", "")
        .putLong("startedAt", System.currentTimeMillis())
        .commit();
    FetchService.request(context, action);
    WorkScheduler.recovery(context);
    publish();
  }

  public static void foregroundStopped() {
    closeViewer();
    if (!SessionState.prefs(context).getBoolean("background", false) && running())
      QueueControl.pause(context, false);
  }

  public static JSONObject snapshot() throws Exception {
    SharedPreferences p = SessionState.prefs(context);
    JSONObject out = new JSONObject();
    JSONObject resolution;
    int total = 0;
    Map<String, Integer> counts;
    try (QueueStore q = new QueueStore(context)) {
      counts = q.counts();
      out.put("phases", new JSONObject(q.phases()))
          .put("held", q.held())
          .put("recordedOutcomes", q.recordedOutcomes());
      if (!running()) FetcherCatalog.seed(q);
      resolution = FetcherCatalog.resolution(q, -1);
    }
    for (int n : counts.values()) total += n;
    int pending = counts.getOrDefault("PENDING", 0),
        retry = counts.getOrDefault("RETRY_LATER", 0),
        failed = counts.getOrDefault("FAILED", 0),
        success = counts.getOrDefault("SUCCESS", 0),
        existing = counts.getOrDefault("EXISTING", 0),
        skipped =
            counts.getOrDefault("SKIPPED_UNAVAILABLE", 0)
                + counts.getOrDefault("SKIPPED_NO_IMAGE", 0);
    FetchBroker broker = FetchBroker.get(context);
    out.put(
        "process",
        FetchProgress.snapshot(
            p,
            running(),
            QueueControl.activeAccounts,
            broker.tasks.size(),
            false, // Bulk retrieval checks cookies on each profile, without a homepage preflight.
            broker.nextDispatchAt(),
            System.currentTimeMillis()));
    RunPolicy r = RunPolicy.read(p);
    out.put("withinSchedule", r.contains(java.time.ZonedDateTime.now()))
        .put(
            "nextAllowedAt",
            r.nextAllowed(
                Math.max(
                    System.currentTimeMillis(),
                    Math.max(p.getLong("retryAt", 0), p.getLong("systemNotBefore", 0))),
                java.time.ZoneId.systemDefault()))
        .put(
            "outsideScheduleMessage",
            r.contains(java.time.ZonedDateTime.now())
                ? ""
                : ScheduleStatus.outsideMessage(
                    r, System.currentTimeMillis(), java.time.ZoneId.systemDefault()));
    long started = p.getLong("startedAt", 0),
        elapsed =
            p.getLong("elapsedSeconds", 0)
                + (running() && started > 0
                    ? Math.max(0, (System.currentTimeMillis() - started) / 1000)
                    : 0);
    return out.put("total", total)
        .put("success", success)
        .put("existing", existing)
        .put("failed", failed)
        .put("skipped", skipped)
        .put("retry", retry)
        .put("pending", pending)
        .put("processed", total - pending - retry)
        .put("current", QueueControl.current)
        .put("etaSeconds", QueueControl.etaSeconds)
        .put("state", p.getString("state", "Idle"))
        .put("scheduleStatus", ScheduleStatus.text(p, running(), QueueControl.activeAccounts))
        .put("running", running())
        .put("retryAt", p.getLong("retryAt", 0))
        .put("blockedHttp", p.getInt("blockedHttp", 0))
        .put("automaticResume", WorkScheduler.enabled(p))
        .put("resolution", resolution)
        .put("cookiesSaved", InstagramSession.configured(context)).put("manualCookiesSaved", new CookieVault(context).configured()).put("manualSession", InstagramSession.manual(context)).put("instagramSession", InstagramSession.status(context))
        .put("writable", writable())
        .put("minimum", r.minimum)
        .put("maximum", r.maximum)
        .put("start", RunPolicy.time(r.start))
        .put("end", RunPolicy.time(r.end))
        .put("background", p.getBoolean("background", false))
        .put("elapsedSeconds", elapsed)
        .put("blocked", p.getString("blocked", ""))
        .put("imageRevision", host().revision())
        .put("sourceRevision", host().sourceRevision())
        .put("requestedViews", p.getInt("concurrentWebViews", 1))
        .put(
            "effectiveViews",
            running()
                ? QueueControl.effectiveViews
                : ConcurrencyPolicy.initial(context, p.getInt("concurrentWebViews", 1)))
        .put("activeViews", QueueControl.activeViews)
        .put("activeAccounts", QueueControl.activeAccounts)
        .put("peakAccounts", QueueControl.peakAccounts)
        .put("completedPerMinute", QueueControl.completedPerMinute)
        .put("memoryPssKiB", android.os.Debug.getPss())
        .put(
            "folder", host().tree().isEmpty() ? "Not selected" : "Current INSFAV thumbnail folder");
  }

  /** An explicit view query, never included in periodic aggregate status. */
  public static JSONObject results(String status) throws Exception {
    if (!java.util.Arrays.asList(
            "SUCCESS",
            "EXISTING",
            "SKIPPED_UNAVAILABLE",
            "SKIPPED_NO_IMAGE",
            "FAILED",
            "RETRY_LATER")
        .contains(status)) throw new IOException("Unsupported result filter");
    if (!running()) sync();
    org.json.JSONArray users = new org.json.JSONArray();
    try (QueueStore q = new QueueStore(context);
        android.database.Cursor c =
            q.getReadableDatabase()
                .rawQuery(
                    "SELECT username FROM accounts WHERE status=? ORDER BY position",
                    new String[] {status})) {
      while (c.moveToNext()) users.put(c.getString(0));
    }
    return new JSONObject().put("status", status).put("users", users);
  }

  public static JSONObject resolution(int offset) throws Exception {
    try (QueueStore q = new QueueStore(context)) {
      if (!running()) FetcherCatalog.seed(q);
      return FetcherCatalog.resolution(q, offset);
    }
  }

  public static String exportSettings(char[] password, JSONObject extra) throws Exception {
    return SettingsArchive.export(context, password, extra);
  }

  public static JSONObject inspectSettings(String data, char[] password) throws Exception {
    return SettingsArchive.inspect(context, data, password);
  }

  public static JSONObject inspectOriginalSettings(String data, char[] password) throws Exception {
    return SettingsArchive.inspectOriginal(context, data, password);
  }

  public static void restoreSettings(JSONObject data) throws Exception {
    restoreSettings(data, null);
  }

  public static void restoreSettings(JSONObject data, SettingsArchive.Extras extras)
      throws Exception {
    if (running() || !viewerToken.isEmpty())
      throw new IOException("Pause retrieval and close the viewer before importing settings.");
    if (extras == null) SettingsArchive.apply(context, data);
    else SettingsArchive.apply(context, data, extras);
    WorkScheduler.cancel(context);
    WorkScheduler.recovery(context);
    WorkScheduler.plan(context, false);
    publish();
  }

  public static String diagnostics() throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      new Diagnostics(context).export(out);
      return out.toString("UTF-8");
    }
  }

  public static synchronized String fullMime(String token) {
    return fullMime;
  }

  public static synchronized byte[] full(String token) {
    return token.equals(viewerToken) && fullBytes != null ? fullBytes : new byte[0];
  }

  public static synchronized void closeViewer() {
    viewerToken = "";
    generation.incrementAndGet();
    if (viewing != null) {
      viewing.cancel(false);
      viewing = null;
    }
    if (context != null) FetchBroker.get(context).cancelViewer();
    if (fullBytes != null) {
      Arrays.fill(fullBytes, (byte) 0);
      fullBytes = null;
    }
  }

  public static void view(String name, String token) {
    closeViewer();
    if (!Identity.valid(name) || !token.matches("[a-zA-Z0-9_-]{1,80}")) return;
    if (!InstagramSession.configured(context)) {
      for (Listener l : listeners) l.fullFailed(token, "COOKIE_REQUIRED");
      return;
    }
    final long id = generation.get();
    viewerToken = token;
    CompletableFuture<byte[]> future = FetchBroker.get(context).submit(name, true);
    viewing = future;
    future.whenCompleteAsync(
        (bytes, error) -> {
          try {
            if (error != null) {
              for (Listener l : listeners)
                if (id == generation.get()) l.fullFailed(token, "REMOTE_IMAGE_UNAVAILABLE");
              return;
            }
            // Validate the whole container and decoder before replacing an existing view or
            // thumbnail.
            ThumbnailCodec.convert(bytes);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            byte[] thumbnailSource = null;
            synchronized (Fetcher.class) {
              if (id != generation.get() || !token.equals(viewerToken)) {
                Arrays.fill(bytes, (byte) 0);
                return;
              }
              fullBytes = bytes;
              fullMime = bounds.outMimeType;
              viewing = null;
              try { thumbnailSource = bytes.clone(); } catch (OutOfMemoryError ignored) { }
            }
            for (Listener l : listeners)
              if (id == generation.get())
                l.fullReady(name, token, bounds.outWidth, bounds.outHeight);
            if (thumbnailSource != null && HdThumbnailWriter.save(context, host(), name, thumbnailSource))
              for (Listener l : listeners) l.changed();
          } catch (OutOfMemoryError issue) {
            if (bytes != null && bytes != fullBytes) Arrays.fill(bytes, (byte) 0);
            memoryFailure();
            new Diagnostics(context).log("IMAGE", "MEMORY_LIMIT", 0);
          } catch (Exception issue) {
            if (bytes != null && bytes != fullBytes) Arrays.fill(bytes, (byte) 0);
            if (id == generation.get() && bytes != fullBytes)
              for (Listener l : listeners) l.fullFailed(token, "IMAGE_VALIDATION_FAILED");
            new Diagnostics(context).log("IMAGE", "VIEW_OR_REFRESH_FAILED", 0);
          } finally {
            if (id == generation.get()) viewing = null;
            publish();
          }
        });
  }

  private Fetcher() {}
}
