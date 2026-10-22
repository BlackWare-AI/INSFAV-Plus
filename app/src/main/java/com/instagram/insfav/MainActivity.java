package com.instagram.insfav;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import com.instagram.insfav.fetcher.Fetcher;
import com.instagram.insfav.fetcher.SettingsArchive;
import com.instagram.insfav.fetcher.SettingsBackupUi;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  private WebView web;
  private volatile boolean foreground;
  private Store store;
  private LocalImages images;
  private ThumbnailInventory inventory;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService scans = Executors.newSingleThreadExecutor();
  private String pendingExport;
  private boolean clientProfileDeltas;
  private long deliveredRevision = -1;
  private String deliveredScope = "";

  private JSONObject imageSnapshot(boolean full) throws Exception {
    JSONObject out =
        images.snapshotChanges(
            deliveredRevision,
            deliveredScope,
            full || (!clientProfileDeltas && deliveredRevision != images.revision()));
    deliveredRevision = out.optLong("revision");
    deliveredScope = out.optString("scopeRevision");
    out.put("scanPending", scanPending.get());
    return out;
  }

  private SettingsBackupUi settingsBackup;
  private final DoubleBackExit exitBack = new DoubleBackExit();
  private volatile android.os.CancellationSignal scanCancellation;
  private final java.util.concurrent.atomic.AtomicBoolean scanPending =
      new java.util.concurrent.atomic.AtomicBoolean();
  private static final int IMPORT = 20, EXPORT = 21, FOLDER = 22, WRITE_FOLDER = 23;
  private final Fetcher.Listener fetchListener =
      new Fetcher.Listener() {
        public void changed() {
          if (!foreground) return;
          if (!io.isShutdown())
            try {
              io.execute(
                  () -> {
                    try {
                      send("fetcher-status", Fetcher.snapshot());
                    } catch (Exception ignored) {
                    }
                  });
            } catch (RejectedExecutionException ignored) {
            }
        }

        public void fullReady(String user, String token, int width, int height) {
          try {
            send(
                "remote-full",
                new JSONObject()
                    .put("user", user)
                    .put("token", token)
                    .put("width", width)
                    .put("height", height));
          } catch (Exception ignored) {
          }
        }

        public void fullFailed(String token, String code) {
          try {
            send("remote-full-failed", new JSONObject().put("token", token).put("code", code));
          } catch (Exception ignored) {
          }
        }
      };

  @android.annotation.SuppressLint(
      "SetJavaScriptEnabled") // Trusted local UI only; imported labels use textContent and CSP
  // denies remote scripts.
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (getApplication() instanceof InsfavApplication
        && !((InsfavApplication) getApplication()).ensureReady()) {
      nativeRecovery();
      return;
    }
    Fetcher.addListener(fetchListener);
    store = Store.get(this);
    images = new LocalImages(this, store);
    inventory = new ThumbnailInventory(this, store, images);
    settingsBackup =
        new SettingsBackupUi(
            this,
            io,
            new SettingsBackupUi.Host() {
              public String export(char[] pass) throws Exception {
                JSONObject extra =
                    new JSONObject().put("language", UiLanguage.choice(MainActivity.this));
                synchronized (store) {
                  for (String k : new String[] {"treeUri", "directoryId", "folderLabel"})
                    if (store.settings.has(k)) extra.put(k, store.settings.getString(k));
                }
                return Fetcher.exportSettings(pass, extra);
              }

              public JSONObject inspect(String raw, char[] pass) throws Exception {
                return validateSettings(Fetcher.inspectSettings(raw, pass));
              }

              public JSONObject inspectOriginal(String raw, char[] pass) throws Exception {
                return validateSettings(Fetcher.inspectOriginalSettings(raw, pass));
              }

              private JSONObject validateSettings(JSONObject data) throws Exception {
                JSONObject extra = data.getJSONObject("extra");
                for (java.util.Iterator<String> i = extra.keys(); i.hasNext(); ) {
                  String key = i.next();
                  if (!java.util.Arrays.asList("treeUri", "directoryId", "folderLabel", "language")
                          .contains(key)
                      || !(extra.get(key) instanceof String))
                    throw new IOException("Invalid settings.");
                }
                if (extra.has("language")
                    && !java.util.Arrays.asList("en", "tr", "system")
                        .contains(extra.getString("language")))
                  throw new IOException("Invalid language setting.");
                return data;
              }

              public void apply(JSONObject data) throws Exception {
                if (images.scanning() || scanPending.get())
                  throw new IOException("Wait for the scan to finish.");
                final JSONObject beforeSettings, beforeIndex;
                final String beforeLanguage = UiLanguage.choice(MainActivity.this);
                synchronized (store) {
                  store.flushIndex();
                  beforeSettings = new JSONObject(store.settings.toString());
                  beforeIndex = new JSONObject(store.index.toString());
                }
                JSONObject extra = data.getJSONObject("extra");
                String tree = extra.optString("treeUri");
                boolean granted = false;
                for (UriPermission grant : getContentResolver().getPersistedUriPermissions())
                  if (grant.getUri().toString().equals(tree) && grant.isReadPermission())
                    granted = true;
                Fetcher.restoreSettings(
                    data,
                    new SettingsArchive.Extras() {
                      public void apply() throws Exception {
                        if (extra.has("language"))
                          UiLanguage.save(MainActivity.this, extra.getString("language"));
                        restoreFolderSelection(store, extra);
                      }

                      public void rollback() throws Exception {
                        synchronized (store) {
                          store.settings = beforeSettings;
                          store.saveSettings();
                          store.replaceIndex(beforeIndex);
                        }
                        UiLanguage.save(MainActivity.this, beforeLanguage);
                      }
                    });
                send(
                    "language",
                    new JSONObject().put("choice", UiLanguage.choice(MainActivity.this)));
                if (!tree.isEmpty() && !granted)
                  send(
                      "message",
                      "Settings restored. Select the thumbnail folder again to grant access.");
                images.checkAccess();
                send("status", imageSnapshot(false));
                Fetcher.sync();
                send("fetcher-status", Fetcher.snapshot());
              }

              public void message(String text) {
                send("message", text);
              }
            });
    getWindow().setStatusBarColor(Color.BLACK);
    getWindow().setNavigationBarColor(Color.BLACK);
    getWindow().getDecorView().setSystemUiVisibility(0);
    if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      getWindow()
          .getInsetsController()
          .setSystemBarsAppearance(
              0,
              WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                  | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else {
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          }
          return insets.consumeSystemWindowInsets();
        });
    web = new WebView(this);
    web.setBackgroundColor(Color.BLACK);
    web.setOverScrollMode(View.OVER_SCROLL_NEVER);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(false);
    s.setCacheMode(WebSettings.LOAD_DEFAULT);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    s.setSupportZoom(false);
    s.setMediaPlaybackRequiresUserGesture(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
    WebView.setWebContentsDebuggingEnabled(false);
    web.addJavascriptInterface(new Bridge(), "Android");
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public WebResourceResponse shouldInterceptRequest(
              WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!"https".equals(uri.getScheme()) || !"insfav.local".equals(uri.getHost()))
              return response("text/plain", new byte[0]);
            String path = uri.getPath();
            try {
              if (path.startsWith("/remote-full/")) {
                byte[] data = Fetcher.full(uri.getLastPathSegment());
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Cache-Control", "no-store, no-cache, must-revalidate");
                return new WebResourceResponse(
                    Fetcher.fullMime(uri.getLastPathSegment()),
                    null,
                    200,
                    "OK",
                    headers,
                    new ByteArrayInputStream(data));
              }
              if (path.startsWith("/thumb/") || path.startsWith("/full/")) {
                if (uri.getQueryParameter("preview") != null)
                  return new WebResourceResponse(
                      "application/octet-stream",
                      null,
                      200,
                      "OK",
                      java.util.Collections.singletonMap("Cache-Control", "no-store"),
                      inventory.open(uri.getQueryParameter("preview")));
                String user = Store.normalize(uri.getLastPathSegment());
                if (!user.matches("[a-z0-9._]{1,30}")) return response("image/webp", new byte[0]);
                if (path.startsWith("/full/"))
                  return response("image/png", images.image(user, true));
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Cache-Control", "private, max-age=300");
                return new WebResourceResponse(
                    images.mime(user), null, 200, "OK", headers, images.openImage(user));
              }
              String name = "/".equals(path) ? "index.html" : path.substring(1);
              if (!name.matches("[a-zA-Z0-9._-]+")) return response("text/plain", new byte[0]);
              String mime =
                  name.endsWith(".woff2")
                      ? "font/woff2"
                      : name.endsWith(".webp")
                          ? "image/webp"
                          : name.endsWith(".js")
                              ? "application/javascript"
                              : name.endsWith(".css") ? "text/css" : "text/html";
              return new WebResourceResponse(
                  mime,
                  "UTF-8",
                  200,
                  "OK",
                  java.util.Collections.singletonMap("Cache-Control", "no-store"),
                  getAssets().open(name));
            } catch (Exception error) {
              store.log("local_resource", "READ_ERROR");
              return response("text/plain", new byte[0]);
            }
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return true;
          }

          @Override
          public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            store.log("web_renderer", "stopped");
            root.removeView(view);
            web = null;
            view.destroy();
            recreate();
            return true;
          }
        });
    root.addView(web, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);
    root.requestApplyInsets();
    web.loadUrl("https://insfav.local/index.html");
  }

  /**
   * Restores the selected URI, never its permissions. Readers/writers still verify real SAF grants.
   */
  static void restoreFolderSelection(Store store, JSONObject extra) throws Exception {
    synchronized (store) {
      String oldTree = store.settings.optString("treeUri"),
          oldDirectory = store.settings.optString("directoryId");
      for (String key : new String[] {"treeUri", "directoryId", "folderLabel"})
        if (extra.has(key)) store.settings.put(key, extra.getString(key));
      store.saveSettings();
      if (!oldTree.equals(store.settings.optString("treeUri"))
          || !oldDirectory.equals(store.settings.optString("directoryId")))
        store.replaceIndex(new JSONObject());
    }
  }

  private void nativeRecovery() {
    android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
    layout.setOrientation(android.widget.LinearLayout.VERTICAL);
    layout.setGravity(android.view.Gravity.CENTER);
    layout.setPadding(32, 32, 32, 32);
    layout.setBackgroundColor(Color.BLACK);
    android.widget.TextView text = new android.widget.TextView(this);
    text.setTextColor(Color.WHITE);
    text.setText(
        UiText.text(this, "Unable to start INSFAV Plus. Your source files are preserved."));
    layout.addView(text);
    for (String label : new String[] {"Retry", "Export Diagnostics"}) {
      android.widget.Button button = new android.widget.Button(this);
      button.setText(UiText.text(this, label));
      button.setOnClickListener(
          v -> {
            if (label.equals("Retry")) recreate();
            else
              export(
                  "INSFAV Plus "
                      + BuildConfig.VERSION_NAME
                      + " build "
                      + BuildConfig.VERSION_CODE
                      + "\nAndroid SDK "
                      + Build.VERSION.SDK_INT
                      + "\n"
                      + "BOOTSTRAP "
                      + ((InsfavApplication) getApplication()).bootstrapFailure()
                      + "\n"
                      + "No account identifiers or credentials included.",
                  "INSFAV-Plus-bootstrap.txt",
                  "text/plain");
          });
      layout.addView(button);
    }
    setContentView(layout);
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    if (images == null) return;
    io.execute(
        () -> {
          try {
            Fetcher.watchSource();
          } catch (RuntimeException unavailable) {
            store.log("source_observer", "UNAVAILABLE");
          }
          images.checkAccess();
          try {
            send(
                "status",
                imageSnapshot(
                    deliveredRevision != images.revision()
                        || !deliveredScope.equals(images.scopeRevision())));
          } catch (Exception error) {
            error(error);
          }
        });
  }

  private WebResourceResponse response(String mime, byte[] bytes) {
    return new WebResourceResponse(
        mime,
        null,
        200,
        "OK",
        java.util.Collections.singletonMap("Cache-Control", "no-store"),
        new ByteArrayInputStream(bytes));
  }

  private void send(String event, Object data) {
    runOnUiThread(
        () -> {
          if (web == null || isDestroyed()) return;
          Object localized =
              data instanceof String && (event.equals("message") || event.equals("error"))
                  ? UiText.text(MainActivity.this, (String) data)
                  : data;
          String value =
              localized instanceof JSONObject
                  ? localized.toString()
                  : JSONObject.quote(String.valueOf(localized));
          web.evaluateJavascript(
              "window.nativeEvent&&window.nativeEvent("
                  + JSONObject.quote(event)
                  + ","
                  + value
                  + ")",
              null);
        });
  }

  private void error(Exception error) {
    if (store != null) store.log("operation_error", error.getClass().getSimpleName());
    if (web == null)
      android.widget.Toast.makeText(
              this, UiText.text(this, "Operation failed"), android.widget.Toast.LENGTH_LONG)
          .show();
    send("error", error.getMessage() == null ? "Operation failed" : error.getMessage());
  }

  private String diagnostics() throws Exception {
    JSONObject diagnostic = images.snapshot(false);
    return "INSFAV Plus "
        + appInfo()
        + "\nAndroid SDK: "
        + Build.VERSION.SDK_INT
        + "\n"
        + store.logs()
        + "\nStorage bytes: "
        + images.storageAudit()
        + "\nImage I/O: "
        + diagnostic.optJSONObject("imageIO")
        + "\nScan I/O: "
        + diagnostic.getJSONObject("scan").optJSONObject("io")
        + "\nIndex writes: "
        + store.writeMetrics()
        + "\n"
        + Fetcher.diagnostics();
  }

  private JSONObject appInfo() throws Exception {
    PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
    return new JSONObject()
        .put("version", info.versionName)
        .put("build", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
  }

  public final class Bridge {
    @JavascriptInterface
    public void request(String raw) {
      if (io.isShutdown()) return;
      try {
        io.execute(
            () -> {
              try {
                JSONObject input = new JSONObject(raw);
                String command = input.getString("command");
                switch (command) {
                  case "bootstrap":
                    {
                      clientProfileDeltas = input.optBoolean("profileDeltas", false);
                      images.checkAccess();
                      JSONObject result = imageSnapshot(true);
                      result
                          .put("language", UiLanguage.choice(MainActivity.this))
                          .put("raw", store.original())
                          .put("app", appInfo())
                          .put(
                              "stats",
                              new JSONObject()
                                  .put("usage", incrementUsage())
                                  .put("load", getPreferences(0).getInt("load", 0))
                                  .put("clear", getPreferences(0).getInt("clear", 0)));
                      send("bootstrap", result);
                      break;
                    }
                  case "language":
                    UiLanguage.save(MainActivity.this, input.getString("choice"));
                    send(
                        "language",
                        new JSONObject().put("choice", UiLanguage.choice(MainActivity.this)));
                    break;
                  case "status":
                    send("status", imageSnapshot(false));
                    break;
                  case "copy-missing-users":
                    {
                      String text = images.missingUsernames();
                      runOnUiThread(
                          () -> {
                            try {
                              int count = MissingProfiles.copy(MainActivity.this, text);
                              send(
                                  "message",
                                  count == 0
                                      ? "No missing usernames."
                                      : count + " usernames copied.");
                            } catch (RuntimeException e) {
                              send(
                                  "error",
                                  "Clipboard unavailable. Try again while INSFAV is open.");
                            }
                          });
                      break;
                    }
                  case "resolution-stats":
                    send("resolution-stats", Fetcher.resolution(-1));
                    break;
                  case "resolution-details":
                    send("resolution-details", Fetcher.resolution(input.optInt("offset", 0)));
                    break;
                  case "fetcher-status":
                    if (!Fetcher.running()) Fetcher.sync();
                    send("fetcher-status", Fetcher.snapshot());
                    break;
                  case "fetcher-paste":
                    {
                      android.content.ClipboardManager clipboard =
                          getSystemService(android.content.ClipboardManager.class);
                      android.content.ClipData clip = clipboard.getPrimaryClip();
                      CharSequence text =
                          clip == null || clip.getItemCount() == 0
                              ? null
                              : clip.getItemAt(0).getText();
                      if (text == null)
                        throw new IOException("No plain text is available in the clipboard.");
                      if (text.length() > 131072)
                        throw new IOException("Cookie text is too large.");
                      send("cookie-paste", text.toString());
                      break;
                    }
                  case "fetcher-cookies-save":
                    Fetcher.saveCookies(input.getString("text"));
                    send("fetcher-status", Fetcher.snapshot());
                    send("message", "Cookies saved securely.");
                    break;
                  case "fetcher-cookies-remove":
                    Fetcher.removeCookies();
                    send("fetcher-status", Fetcher.snapshot());
                    break;
                  case "fetcher-config":
                    Fetcher.configure(
                        input.getInt("minimum"),
                        input.getInt("maximum"),
                        input.getString("start"),
                        input.getString("end"),
                        input.optBoolean("background"),
                        input.optInt("concurrentWebViews", 1));
                    send("fetcher-status", Fetcher.snapshot());
                    break;
                  case "fetcher-action":
                    if (images.scanning())
                      throw new IOException("Wait for the thumbnail scan to finish.");
                    runOnUiThread(
                        () -> {
                          if (Build.VERSION.SDK_INT >= 33
                              && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            requestPermissions(
                                new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 24);
                        });
                    Fetcher.action(input.getString("action"));
                    break;
                  case "fetcher-write-access":
                    grantFetcherWrite();
                    break;
                  case "fetcher-logs":
                    export(diagnostics(), "INSFAV-Plus-diagnostics.txt", "text/plain");
                    break;
                  case "view-remote":
                    Fetcher.view(
                        Store.normalize(input.getString("user")), input.getString("token"));
                    break;
                  case "view-closed":
                    Fetcher.closeViewer();
                    runOnUiThread(
                        () -> {
                          if (web != null) web.clearCache(true);
                        });
                    break;
                  case "select-folder":
                    selectFolder();
                    break;
                  case "cancel-scan":
                    if (scanCancellation != null) scanCancellation.cancel();
                    images.cancelScan();
                    send(
                        "message",
                        "Scan cancellation requested. Completed checkpoints are preserved.");
                    break;
                  case "rescan":
                    if (Fetcher.running())
                      throw new IOException("Pause the fetcher before rescanning.");
                    startScan(false);
                    break;
                  case "clear-cache":
                    runOnUiThread(
                        () -> {
                          if (web != null) web.clearCache(true);
                        });
                    images.clearCache();
                    images.clearLegacyCache();
                    send("image-cache-cleared", new JSONObject());
                    send("status", imageSnapshot(false));
                    send(
                        "message",
                        "Image cache cleared. Source photos and collections are unchanged.");
                    break;
                  case "clear-index":
                    if (Fetcher.running())
                      throw new IOException("Pause the fetcher before clearing its image index.");
                    images.clearIndex();
                    send("status", imageSnapshot(false));
                    send(
                        "message",
                        "Thumbnail index cleared. Source photos and collections are unchanged.");
                    break;
                  case "image-failed":
                    send("status", imageSnapshot(false));
                    break;
                  case "import":
                    runOnUiThread(
                        () -> {
                          Intent intent =
                              new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                  .setType("*/*")
                                  .addCategory(Intent.CATEGORY_OPENABLE);
                          intent.putExtra(
                              Intent.EXTRA_MIME_TYPES,
                              new String[] {
                                "application/json", "text/plain", "application/octet-stream"
                              });
                          startActivityForResult(intent, IMPORT);
                        });
                    break;
                  case "export":
                    if (store.original().isEmpty())
                      throw new IOException("No collections to export");
                    export(store.original(), "INSFAV-Plus-backup.json", "application/json");
                    break;
                  case "settings-export":
                    settingsBackup.start(true);
                    break;
                  case "settings-import":
                    settingsBackup.start(false);
                    break;
                  case "settings-import-original":
                    settingsBackup.startOriginal();
                    break;
                  case "inventory":
                    send(
                        "inventory",
                        inventory
                            .query(
                                input.getString("kind"),
                                input.optInt("offset", 0),
                                input.optString("query"))
                            .put("requestId", input.optLong("requestId")));
                    break;
                  case "fetcher-results":
                    send("fetcher-results", Fetcher.results(input.getString("status")));
                    break;
                  case "performance":
                    send("performance", PerformanceReport.read(store, images, Fetcher.snapshot()));
                    break;
                  case "history":
                    send(
                        "history",
                        new JSONObject().put("items", OperationHistory.read(MainActivity.this)));
                    break;
                  case "logs":
                    export(diagnostics(), "INSFAV-Plus-diagnostics.txt", "text/plain");
                    break;
                  case "frontend-error":
                    store.log("web_error", input.optString("code", "script"));
                    break;
                  case "clear-data":
                    if (images.scanning() || scanPending.get())
                      throw new IOException("Wait for the scan to finish.");
                    if (Fetcher.running())
                      throw new IOException("Pause the fetcher before clearing collections.");
                    store.clearDocument();
                    getPreferences(0)
                        .edit()
                        .putInt("clear", getPreferences(0).getInt("clear", 0) + 1)
                        .apply();
                    send("cleared", "");
                    break;
                  case "open":
                    openInstagram(input.getString("user"));
                    break;
                  default:
                    throw new IOException("Unsupported operation");
                }
              } catch (Exception error) {
                error(error);
              }
            });
      } catch (RejectedExecutionException ignored) {
      }
    }
  }

  private int incrementUsage() {
    int value = getPreferences(0).getInt("usage", 0) + 1;
    getPreferences(0).edit().putInt("usage", value).apply();
    return value;
  }

  private void selectFolder() {
    if (Fetcher.running()) {
      send("error", "Pause the fetcher before changing folders.");
      return;
    }
    if (images.scanning() || scanPending.get()) {
      send("error", "Wait for the scan to finish.");
      return;
    }
    runOnUiThread(
        () -> {
          Intent intent =
              new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                  .addFlags(
                      Intent.FLAG_GRANT_READ_URI_PERMISSION
                          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                          | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
          String current;
          synchronized (store) {
            current = store.settings.optString("treeUri");
          }
          if (!current.isEmpty())
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(current));
          startActivityForResult(intent, FOLDER);
        });
  }

  private void grantFetcherWrite() {
    String current;
    synchronized (store) {
      current = store.settings.optString("treeUri");
    }
    if (current.isEmpty()) {
      send("error", "Select a thumbnail folder in Profile Images first.");
      return;
    }
    runOnUiThread(
        () -> {
          Intent intent =
              new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                  .addFlags(
                      Intent.FLAG_GRANT_READ_URI_PERMISSION
                          | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                          | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
          intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(current));
          startActivityForResult(intent, WRITE_FOLDER);
        });
  }

  private void startScan(boolean cleanupRequested) {
    if (!scanPending.compareAndSet(false, true)) {
      send("message", "A scan is already in progress.");
      return;
    }
    android.os.CancellationSignal cancellation = new android.os.CancellationSignal();
    scanCancellation = cancellation;
    try {
      send("status", imageSnapshot(false));
    } catch (Exception error) {
      error(error);
    }
    scans.execute(
        () -> {
          try {
            images.scan((event, result) -> send(event, result), cleanupRequested, cancellation);
          } finally {
            scanPending.set(false);
            scanCancellation = null;
            try {
              send("status", imageSnapshot(false));
            } catch (Exception error) {
              error(error);
            }
          }
        });
  }

  private void openInstagram(String name) {
    String user = Store.normalize(name);
    if (!user.matches("[a-z0-9._]{1,30}")) {
      send("error", "Invalid Instagram username");
      return;
    }
    runOnUiThread(
        () -> {
          try {
            startActivity(
                new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("instagram://user?username=" + Uri.encode(user))));
          } catch (ActivityNotFoundException error) {
            try {
              startActivity(
                  new Intent(
                      Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/" + user + "/")));
            } catch (ActivityNotFoundException missing) {
              send("error", "No app can open this profile.");
            }
          }
        });
  }

  private void export(String content, String name, String mime) {
    if (pendingExport != null) {
      send("error", "Finish the current export first.");
      return;
    }
    pendingExport = content;
    runOnUiThread(
        () ->
            startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .setType(mime)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, name),
                EXPORT));
  }

  @Override
  protected void onActivityResult(int request, int result, Intent intent) {
    super.onActivityResult(request, result, intent);
    if (settingsBackup != null && settingsBackup.result(request, result, intent)) return;
    if (result != RESULT_OK || intent == null || intent.getData() == null) {
      if (request == EXPORT) pendingExport = null;
      return;
    }
    Uri uri = intent.getData();
    int flags = intent.getFlags();
    io.execute(
        () -> {
          try {
            if (request == WRITE_FOLDER) {
              String current;
              synchronized (store) {
                current = store.settings.optString("treeUri");
              }
              if (!uri.toString().equals(current))
                throw new IOException(
                    "Select the same INSFAV thumbnail folder. The existing folder and index were"
                        + " preserved.");
              int grant =
                  flags
                      & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                          | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
              if (grant
                  != (Intent.FLAG_GRANT_READ_URI_PERMISSION
                      | Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                throw new IOException("Folder read/write access was not granted.");
              getContentResolver()
                  .takePersistableUriPermission(
                      uri,
                      Intent.FLAG_GRANT_READ_URI_PERMISSION
                          | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
              Fetcher.sync();
              send("fetcher-status", Fetcher.snapshot());
            } else if (request == FOLDER) {
              images.select(uri, flags);
              send("status", imageSnapshot(false));
              startScan(false);
            } else if (request == IMPORT) {
              String raw;
              try (InputStream input = getContentResolver().openInputStream(uri);
                  ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IOException("Document cannot be opened");
                byte[] bytes = new byte[8192];
                int length;
                while ((length = input.read(bytes)) != -1) {
                  if (output.size() + length > 32 * 1024 * 1024)
                    throw new IOException("JSON exceeds 32 MiB");
                  output.write(bytes, 0, length);
                }
                raw = output.toString("UTF-8");
              }
              if (Fetcher.running() || images.scanning() || scanPending.get())
                throw new IOException(
                    "Pause the fetcher and wait for scans before importing collections.");
              store.importDocument(raw);
              getPreferences(0)
                  .edit()
                  .putInt("load", getPreferences(0).getInt("load", 0) + 1)
                  .apply();
              JSONObject snapshot = imageSnapshot(true);
              snapshot.put("raw", raw);
              send("imported", snapshot);
            } else if (request == EXPORT && pendingExport != null) {
              try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("Destination cannot be opened");
                output.write(pendingExport.getBytes(StandardCharsets.UTF_8));
              }
              pendingExport = null;
              store.log("export", "success");
              send("message", "Export complete.");
            }
          } catch (Exception error) {
            if (request == EXPORT) pendingExport = null;
            error(error);
          }
        });
  }

  @Override
  public void onBackPressed() {
    if (web == null) {
      requestExit();
      return;
    }
    web.evaluateJavascript(
        "window.handleBack&&window.handleBack()",
        value -> {
          if ("true".equals(value)) exitBack.reset();
          else requestExit();
        });
  }

  private void requestExit() {
    if (!foreground || isFinishing() || isDestroyed()) return;
    if (exitBack.press(SystemClock.elapsedRealtime())) super.onBackPressed();
    else if (web != null) send("message", "Press back again to exit.");
    else
      android.widget.Toast.makeText(
              this, "Press back again to exit.", android.widget.Toast.LENGTH_SHORT)
          .show();
  }

  @Override
  protected void onStop() {
    exitBack.reset();
    foreground = false;
    if (images != null) Fetcher.foregroundStopped();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    Fetcher.removeListener(fetchListener);
    Fetcher.closeViewer();
    if (scanCancellation != null) scanCancellation.cancel();
    if (images != null) {
      images.cancelScan();
      images.closeStreams();
    }
    if (inventory != null) inventory.close();
    scans.shutdownNow();
    io.shutdownNow();
    if (web != null) {
      web.removeJavascriptInterface("Android");
      web.destroy();
      web = null;
    }
    super.onDestroy();
  }
}
