package com.instagram.insfav.fetcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.*;
import android.webkit.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Bounded reusable pages, one shared cookie lease; browser operations run on the main looper. */
final class WebViewProfileProvider implements ProfileProvider {
  static final long PROFILE_DEADLINE_MS = 45000;
  static final long SESSION_DEADLINE_MS = 10000;

  interface Pages {
    void load(WebView view, String url);
  }

  interface Resources {
    WebResourceResponse intercept(String url);
  }

  private final Pages pages;
  private final Resources resources;

  interface Downloads {
    PublicProfileProvider open();
  }

  private final Downloads downloads;
  volatile int createdCount, reusedCount, activeCount;
  private final Context context;
  private final CookieJar cookies;
    // Injected offline fixture constructors use their fixture store; production isolates manual cookies.
    private boolean isolatedManual;
    private volatile CookieManager sessionManager;
    private volatile String sessionAccount = "";
    private final Object authenticationLock = new Object();
    private volatile boolean persistentAuthenticated;

  static final Semaphore SESSION_LEASE = new Semaphore(1, true);
  private final Semaphore imageTransfers = new Semaphore(30, true);
  private final ConcurrentHashMap<String, PublicProfileProvider> userTransfers =
      new ConcurrentHashMap<>();
  private final Set<PublicProfileProvider> transfers = ConcurrentHashMap.newKeySet();
  private final Set<Operation> operations = ConcurrentHashMap.newKeySet();
  // These collections are main-looper confined.
  private final ArrayDeque<WebView> idle = new ArrayDeque<>();
  private final Set<WebView> views = new HashSet<>();
  private final CountDownLatch sessionReady = new CountDownLatch(1);
  private final java.util.concurrent.atomic.AtomicBoolean initializing =
      new java.util.concurrent.atomic.AtomicBoolean();
  private volatile Failure sessionFailure, barrier;
  private volatile boolean leased;
  private volatile int ceiling = 1;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Diagnostics log;
  private final String probe, hdProbe;

  private volatile boolean cancelled;
  private final java.util.concurrent.atomic.AtomicBoolean closing =
      new java.util.concurrent.atomic.AtomicBoolean();

  WebViewProfileProvider(Context context, CookieJar cookies) throws Failure {
    this(context, cookies, (view, url) -> view.loadUrl(url), url -> null);
        isolatedManual = cookies != null;
  }

  WebViewProfileProvider(Context context, CookieJar cookies, Pages pages, Resources resources)
      throws Failure {
    this(context, cookies, pages, resources, PublicProfileProvider::new);
  }

  WebViewProfileProvider(
      Context context, CookieJar cookies, Pages pages, Resources resources, Downloads downloads)
      throws Failure {
    this.downloads = downloads;
    this.pages = pages;
    this.resources = resources;
    this.context = context.getApplicationContext();
    this.cookies = cookies;
    log = new Diagnostics(this.context);
    ceiling =
        ConcurrencyPolicy.initial(
            context, SessionState.prefs(context).getInt("concurrentWebViews", 1));
    try (InputStream input = context.getAssets().open("profile-probe.js");
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int n;
      while ((n = input.read(buffer)) != -1) {
        if (output.size() + n > 32768) throw new IOException();
        output.write(buffer, 0, n);
      }
      probe = new String(output.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new Failure("WEBVIEW_PROBE_UNAVAILABLE", 0, 0, true);
    }
    try (InputStream in = context.getAssets().open("profile-probe-hd.js")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) != -1) {
        if (out.size() + n > 32768) throw new IOException();
        out.write(b, 0, n);
      }
      hdProbe = out.toString("UTF-8");
    } catch (IOException e) {
      throw new Failure("WEBVIEW_PROBE_UNAVAILABLE");
    }
  }

  private volatile boolean verifiedSession;

  @Override
  public void authenticate() throws Failure {
    if (sessionReady.getCount() == 0 && sessionFailure == null)
      log.session("SHARED_COOKIES_REUSED");
    log.session("AUTHENTICATION_STARTED");
    verifiedSession = false;
    try {
      resolve("", false);
      verifiedSession = true;
      log.session("SESSION_VERIFIED");
    } catch (Failure e) {
      log.session(
          e.code.equals("COOKIE_INVALID_EXPIRED") ? "SESSION_UNVERIFIED" : "SESSION_UNKNOWN");
      throw e;
    }
  }

  @Override
  public byte[] fetch(String user) throws Failure {
    return fetch(user, false);
  }

  @Override
  public byte[] fetch(String user, boolean highResolution) throws Failure {
    if (!Identity.valid(user) || !Identity.normalize(user).equals(user))
      throw new Failure("PROFILE_IDENTITY_UNVERIFIED");
    JSONObject result = resolve(user, highResolution);
    URL image = WebProfilePolicy.image(result, user);
    if (cancelled) throw new Failure("REQUEST_CANCELLED");
    PublicProfileProvider transfer = downloads.open();
    try {
      imageTransfers.acquire();
      try {
        if (cancelled || barrier != null) throw new Failure("REQUEST_CANCELLED");
        transfers.add(transfer);
        userTransfers.put(user, transfer);
        return transfer.download(image);
      } finally {
        transfers.remove(transfer);
        userTransfers.remove(user, transfer);
        imageTransfers.release();
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new Failure("REQUEST_CANCELLED");
    } catch (Failure error) {
      if (FailurePolicy.pause(error)) trip(error);
      throw error;
    }
  }

  JSONObject resolve(String user, boolean highResolution) throws Failure {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new Failure("WEBVIEW_WORKER_REQUIRED", 0, 0, true);
    if (cancelled) throw new Failure("REQUEST_CANCELLED");
    if (cookies != null && cookies.value("sessionid", System.currentTimeMillis()).isEmpty())
      throw new Failure("COOKIE_EXPIRED", 0, 0, true);
    ensureSession();
    if (barrier != null) throw barrier;
        if (cookies == null && !user.isEmpty() && !persistentAuthenticated) {
            synchronized (authenticationLock) {
                if (!persistentAuthenticated) { resolve("", false); persistentAuthenticated = true; }
            }
        }

    Operation operation =
        new Operation(
            user, sessionAccount, highResolution);
    operations.add(operation);
    main.post(operation::start);
    try {
      if (!operation.done.await(
          user.isEmpty() ? 25000 : PROFILE_DEADLINE_MS + 2000, TimeUnit.MILLISECONDS)) {
        main.post(() -> operation.fail(new Failure("WEBVIEW_TIMEOUT")));
        throw new Failure("WEBVIEW_TIMEOUT");
      }
      if (operation.failure != null) throw operation.failure;
      return operation.result;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      main.post(() -> operation.fail(new Failure("REQUEST_CANCELLED")));
      throw new Failure("REQUEST_CANCELLED");
    } finally {
      operations.remove(operation);
    }
  }

  @Override
  public Failure blockingFailure() {
    return barrier;
  }


    private void trip(Failure error) {
        if (barrier != null || error.code.equals("REQUEST_CANCELLED")) return;
        barrier = error;
        InstagramSession.blocked(context, cookies != null, error.code);
        for (PublicProfileProvider transfer : transfers) transfer.cancel();
        main.post(() -> {
            for (Operation operation : new ArrayList<>(operations))
                if (!operation.finishing) operation.fail(error);
        });
    }
  private void ensureSession() throws Failure {
    if (initializing.compareAndSet(false, true)) {
      try {
        if (!SESSION_LEASE.tryAcquire(6, TimeUnit.SECONDS))
          throw new Failure("WEBVIEW_SESSION_BUSY", 0, 0, true);
        leased = true;
        main.post(
            () -> {
              try {
                if (isolatedManual && !InstagramSession.profiles()) throw new Failure("COOKIE_WEBVIEW_UPDATE_REQUIRED", 0, 0, true);
                CookieManager m = isolatedManual ? InstagramSession.manager(true) : CookieManager.getInstance();
                sessionManager = m; m.setAcceptCookie(true);
                if (cookies == null) { sessionAccount = InstagramSession.account(m); sessionReady.countDown(); }
                else { sessionAccount = cookies.value("ds_user_id", System.currentTimeMillis()); m.removeAllCookies(ignored -> applySessionCookies(m, 0)); }
              } catch (Failure error) { sessionFailure = error; sessionReady.countDown();
              } catch (RuntimeException | LinkageError error) {
                sessionFailure = new Failure("WEBVIEW_UNAVAILABLE", 0, 0, true);
                sessionReady.countDown();
              }
            });
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        sessionFailure = new Failure("REQUEST_CANCELLED");
        sessionReady.countDown();
      } catch (Failure error) {
        sessionFailure = error;
        sessionReady.countDown();
      }
    }
    try {
      if (!sessionReady.await(12, TimeUnit.SECONDS))
        throw new Failure("COOKIE_WEBVIEW_SETUP_TIMEOUT", 0, 0, true);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new Failure("REQUEST_CANCELLED");
    }
    if (sessionFailure != null) throw sessionFailure;
    if (cancelled) throw new Failure("REQUEST_CANCELLED");
  }

  void applySessionCookies(CookieManager manager, int index) {
    if (cancelled) {
      sessionFailure = new Failure("REQUEST_CANCELLED");
      sessionReady.countDown();
      return;
    }
    if (index == cookies.entries.size()) {
      log.session("SHARED_COOKIES_APPLIED");
      sessionReady.countDown();
      return;
    }
    CookieJar.Entry entry = cookies.entries.get(index);
    if (entry.expires != 0 && entry.expires <= System.currentTimeMillis() / 1000
        || !(entry.domain.equals("www.instagram.com")
            || entry.domain.equals("instagram.com") && entry.subdomains)) {
      applySessionCookies(manager, index + 1);
      return;
    }
    if (!entry.path.matches("/[A-Za-z0-9_./%~\\-]*")) {
      sessionFailure = new Failure("COOKIE_INVALID", 0, 0, true);
      sessionReady.countDown();
      return;
    }
    String value =
        entry.name
            + "="
            + entry.value
            + "; Path="
            + entry.path
            + "; Secure; SameSite=Lax"
            + (entry.name.equals("sessionid") ? "; HttpOnly" : "");
    try {
      manager.setCookie(
          "https://www.instagram.com/",
          value,
          accepted -> {
            if (!Boolean.TRUE.equals(accepted)) {
              sessionFailure = new Failure("COOKIE_WEBVIEW_REJECTED", 0, 0, true);
              sessionReady.countDown();
            } else applySessionCookies(manager, index + 1);
          });
    } catch (RuntimeException error) {
      sessionFailure = new Failure("WEBVIEW_UNAVAILABLE", 0, 0, true);
      sessionReady.countDown();
    }
  }

  @Override
  public void trim(int limit) {
    ceiling = Math.max(1, limit);
    main.post(
        () -> {
          while (views.size() > ceiling && !idle.isEmpty()) destroy(idle.remove());
        });
  }

  private void destroy(WebView view) {
    idle.remove(view);
    if (views.remove(view)) view.destroy();
  }

  @Override
  public void cancel(String user) {
    PublicProfileProvider transfer = userTransfers.get(user);
    if (transfer != null) transfer.cancel();
    main.post(
        () -> {
          for (Operation operation : new ArrayList<>(operations))
            if (operation.user.equals(user)) operation.fail(new Failure("REQUEST_CANCELLED"));
        });
  }

  @Override
  public void cancel() {
    cancelled = true;
    for (PublicProfileProvider transfer : transfers) transfer.cancel();
    main.post(
        () -> {
          for (Operation operation : new ArrayList<>(operations))
            operation.fail(new Failure("REQUEST_CANCELLED"));
        });
  }

  @Override
  public void close() {
    if (!closing.compareAndSet(false, true)) return;
    cancel();
    CountDownLatch cleaned = new CountDownLatch(1);
    main.post(
        () -> {
          try {
            for (WebView view : new ArrayList<>(views)) destroy(view);
            if (sessionManager != null) sessionManager.flush();
            if (leased) { leased = false; SESSION_LEASE.release(); }
            cleaned.countDown();
          } catch (RuntimeException | LinkageError error) {
            if (leased) { leased = false; SESSION_LEASE.release(); }
            log.log("WEBVIEW", "CLEANUP_FAILED", 0);
            cleaned.countDown();
          }
        });
    if (Looper.myLooper() != Looper.getMainLooper()) {
      boolean interrupted = Thread.interrupted();
      try {
        if (!cleaned.await(5, TimeUnit.SECONDS)) log.log("WEBVIEW", "CLEANUP_TIMEOUT", 0);
      } catch (InterruptedException error) {
        interrupted = true;
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }

  final class Operation {
    final String user, expectedId;
    final boolean highResolution;
    final long profileDeadlineMs;
    final CountDownLatch done = new CountDownLatch(1);
    final Object token = new Object();
    volatile Failure failure;
    volatile JSONObject result;
    WebView view;
    volatile boolean finishing;
    boolean completed, started, broken, counted, timingStarted;
    final java.util.concurrent.atomic.AtomicInteger resourceCount =
        new java.util.concurrent.atomic.AtomicInteger();
    long deadline;
    final long began = SystemClock.uptimeMillis();
    boolean pageFinished, candidateFound;
    int http;
    String sessionEvidence = "UNKNOWN";
    Diagnostics.Reason reason = Diagnostics.Reason.UNKNOWN_RESPONSE;

    Operation(String user, String expectedId, boolean highResolution) {
      this(user, expectedId, highResolution, PROFILE_DEADLINE_MS);
    }

    // Package-private seam for deterministic deadline comparisons; no user/runtime override.
    Operation(String user, String expectedId, boolean highResolution, long profileDeadlineMs) {
      if (profileDeadlineMs <= 0) throw new IllegalArgumentException("Profile deadline");
      this.profileDeadlineMs = profileDeadlineMs;
      this.user = user;
      this.expectedId = expectedId;
      this.highResolution = highResolution;
      sessionEvidence = verifiedSession ? "VERIFIED" : "UNKNOWN";
    }

    void later(Runnable action, long delay) {
      main.postAtTime(action, token, SystemClock.uptimeMillis() + delay);
    }

    void armWatchdog() {
      if (!timingStarted) {
        timingStarted = true;
        deadline = began + (user.isEmpty() ? 18000 : profileDeadlineMs);
        later(
            () -> fail(new Failure("WEBVIEW_TIMEOUT")),
            Math.max(0, deadline - SystemClock.uptimeMillis()));
      }
    }

    @SuppressLint("SetJavaScriptEnabled")
    void start() {
      if (finishing) return;
      if (cancelled) {
        fail(new Failure("REQUEST_CANCELLED"));
        return;
      }
      if (barrier != null) { fail(barrier); return; }
      // Includes creation/cookie setup; no indefinitely pending platform callback.
      armWatchdog();
      try {
        WebView.setWebContentsDebuggingEnabled(false);
        view = idle.poll();
        if (view == null) {
          if (views.size() >= ceiling) {
            later(this::start, 50);
            return;
          }
          view = new WebView(context);
          InstagramSession.attach(view, isolatedManual);
          views.add(view);
          createdCount++;
          log.log("POOL", "VIEW_CREATED", views.size());
        } else {
          reusedCount++;
          log.log("POOL", "VIEW_REUSED", views.size());
        }
        counted = true;
        QueueControl.activeViews = ++activeCount;
        view.setImportantForAutofill(
            android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        view.setSaveEnabled(false);
        // Provide a viewport without attaching a window or showing browser content.
        int w = 1080, h = 1920;
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(
                h, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setGeolocationEnabled(false);
        settings.setOffscreenPreRaster(false);
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        ServiceWorkerWebSettings workerSettings =
            ServiceWorkerController.getInstance().getServiceWorkerWebSettings();
        workerSettings.setAllowFileAccess(false);
        workerSettings.setAllowContentAccess(false);
        workerSettings.setBlockNetworkLoads(true);
        view.setWebViewClient(client());
        view.setWebChromeClient(
            new WebChromeClient() {
              @Override
              public boolean onConsoleMessage(ConsoleMessage message) {
                return true;
              }

              @Override
              public void onPermissionRequest(PermissionRequest request) {
                request.deny();
              }

              @Override
              public void onGeolocationPermissionsShowPrompt(
                  String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
              }

              @Override
              public boolean onJsAlert(WebView v, String url, String message, JsResult result) {
                result.cancel();
                return true;
              }

              @Override
              public boolean onJsConfirm(WebView v, String url, String message, JsResult result) {
                result.cancel();
                return true;
              }

              @Override
              public boolean onJsPrompt(
                  WebView v, String url, String message, String initial, JsPromptResult result) {
                result.cancel();
                return true;
              }

              @Override
              public boolean onShowFileChooser(
                  WebView v, ValueCallback<android.net.Uri[]> callback, FileChooserParams params) {
                callback.onReceiveValue(null);
                return true;
              }
            });
        view.setDownloadListener(
            (url, agent, disposition, type, length) ->
                fail(new Failure("WEBVIEW_DOWNLOAD_BLOCKED", 0, 0, true)));
        CookieManager manager = sessionManager;
        manager.setAcceptCookie(true);
        manager.setAcceptThirdPartyCookies(view, false);
        started = true;
        deadline =
            user.isEmpty()
                ? SystemClock.uptimeMillis() + SESSION_DEADLINE_MS
                : began + profileDeadlineMs;
        if (SystemClock.uptimeMillis() >= deadline) {
          fail(new Failure("WEBVIEW_TIMEOUT"));
          return;
        }
        pages.load(view, "https://www.instagram.com/" + (user.isEmpty() ? "" : user + "/"));
        later(this::poll, 500);
      } catch (OutOfMemoryError error) {
        fail(new Failure("IMAGE_MEMORY_LIMIT", 0, 0, true));
      } catch (RuntimeException | LinkageError error) {
        fail(new Failure("WEBVIEW_UNAVAILABLE", 0, 0, true));
      }
    }

    WebViewClient client() {
      return new WebViewClient() {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
          if (finishing) return true;
          if (!request.isForMainFrame())
            return !WebProfilePolicy.resource(request.getUrl().toString());
          String code = WebProfilePolicy.navigation(request.getUrl().toString(), user);
          if (!code.isEmpty()) {
            fail(new Failure(code, 0, 0, true));
            return true;
          }
          return false;
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap favicon) {
          if (finishing || !started) return;
          pageFinished = false;
          http = 0;
          String code = WebProfilePolicy.navigation(url, user);
          if (!code.isEmpty()) fail(new Failure(code, 0, 0, true));
        }

        @Override
        public void onPageFinished(WebView v, String url) {
          // A finished page alone is not authentication proof.
                if (sessionManager != null) sessionManager.flush();
          if (!finishing) {
            pageFinished = true;
            log.log("WEBVIEW", "PAGE_FINISHED", 0);
          }
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
          if (finishing || cancelled || barrier != null)
            return new WebResourceResponse(
                "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
          if (resourceCount.incrementAndGet() > 600) {
            main.post(() -> fail(new Failure("ACCESS_RESOURCE_LIMIT", 0, 0, true)));
            return new WebResourceResponse(
                "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
          }
          return WebProfilePolicy.resource(request.getUrl().toString())
              ? resources.intercept(request.getUrl().toString())
              : new WebResourceResponse(
                  "text/plain",
                  "UTF-8",
                  403,
                  "Blocked",
                  Collections.emptyMap(),
                  new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public void onReceivedHttpError(
            WebView v, WebResourceRequest request, WebResourceResponse response) {
          String host = request.getUrl().getHost();
          if (finishing || !("www.instagram.com".equals(host) || "instagram.com".equals(host)))
            return;
          if (request.isForMainFrame()) http = response.getStatusCode();
          String retry = null;
          if (response.getResponseHeaders() != null)
            for (Map.Entry<String, String> entry : response.getResponseHeaders().entrySet())
              if (entry.getKey().equalsIgnoreCase("Retry-After")) retry = entry.getValue();
          Failure error =
              WebProfilePolicy.http(response.getStatusCode(), request.isForMainFrame(), retry);
          if (error != null
              && !(request.isForMainFrame() && response.getStatusCode() == 404 && !user.isEmpty()))
            fail(error);
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
          if (request.isForMainFrame() && !finishing)
            fail(new Failure("WEBVIEW_NETWORK_ERROR", 0, 0, true));
        }

        @Override
        public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
          handler.cancel();
          fail(new Failure("TLS_ERROR", 0, 0, true));
        }

        @Override
        public void onReceivedHttpAuthRequest(
            WebView v, HttpAuthHandler handler, String host, String realm) {
          handler.cancel();
          fail(new Failure("ACCESS_BLOCKED", 0, 0, true));
        }

        @Override
        public void onReceivedClientCertRequest(WebView v, ClientCertRequest request) {
          request.cancel();
          fail(new Failure("TLS_ERROR", 0, 0, true));
        }

        @androidx.annotation.RequiresApi(27)
        @Override
        public void onSafeBrowsingHit(
            WebView v, WebResourceRequest request, int type, SafeBrowsingResponse response) {
          response.backToSafety(false);
          fail(new Failure("WEBVIEW_SAFE_BROWSING_BLOCKED", 0, 0, true));
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
          broken = true;
          QueueControl.memoryPressure = true;
          fail(new Failure("WEBVIEW_RENDERER_GONE"));
          return true;
        }
      };
    }

    void poll() {
      if (finishing) return;
      if (cancelled) {
        fail(new Failure("REQUEST_CANCELLED"));
        return;
      }
      if (barrier != null) { fail(barrier); return; }
      Failure expired = ProbeDecision.deadline(SystemClock.uptimeMillis(), deadline);
      if (expired != null) {
        reason = Diagnostics.Reason.DEADLINE;
        fail(expired);
        return;
      }
      try {
        String url = view.getUrl();
        if (url == null || url.equals("about:blank")) {
          later(this::poll, 500);
          return;
        }
        String code = WebProfilePolicy.navigation(url, user);
        if (!code.isEmpty()) {
          fail(new Failure(code, 0, 0, true));
          return;
        }
        view.evaluateJavascript(
            "("
                + (highResolution ? hdProbe : probe)
                + ")("
                + JSONObject.quote(user)
                + ","
                + JSONObject.quote(expectedId)
                + ","
                + highResolution
                + ")",
            encoded -> {
              if (finishing) return;
              try {
                JSONObject data = WebProfilePolicy.snapshot(encoded);
                reason = Diagnostics.reason(data.optString("reason"));
                candidateFound |= data.optBoolean("candidateFound") || data.has("url");
                if (data.optBoolean("authenticated")) sessionEvidence = "VERIFIED";
                // A late JS callback must not turn an expired operation into success or no-image.
                Failure late = ProbeDecision.deadline(SystemClock.uptimeMillis(), deadline);
                if (late != null) {
                  fail(late);
                  return;
                }
                if (user.isEmpty()
                    && data.optBoolean("authenticated")
                    && data.optString("wall").isEmpty()) {
                  finish(data, null);
                  return;
                }
                if (!user.isEmpty() && data.has("url") && data.optString("wall").isEmpty()) {
                  WebProfilePolicy.image(data, user);
                  finish(data, null);
                  return;
                }
                Failure decision =
                    ProbeDecision.failure(data, user, http, verifiedSession, candidateFound);
                if (decision != null) {
                  fail(decision);
                  return;
                }
                later(this::poll, 500);
              } catch (Failure error) {
                fail(error);
              } catch (OutOfMemoryError error) {
                fail(new Failure("IMAGE_MEMORY_LIMIT", 0, 0, true));
              } catch (RuntimeException error) {
                fail(new Failure("WEBVIEW_SCRIPT_ERROR", 0, 0, true));
              }
            });
      } catch (OutOfMemoryError error) {
        fail(new Failure("IMAGE_MEMORY_LIMIT", 0, 0, true));
      } catch (RuntimeException error) {
        fail(new Failure("WEBVIEW_SCRIPT_ERROR", 0, 0, true));
      }
    }

    void fail(Failure error) {
      if (error.code.equals("WEBVIEW_TIMEOUT")) reason = Diagnostics.Reason.DEADLINE;
      if (error.code.equals("COOKIE_INVALID_EXPIRED")) {
        reason = Diagnostics.Reason.COOKIE_OR_LOGIN_GATE;
        sessionEvidence = "UNVERIFIED";
      }
      finish(null, error);
    }

    void finish(JSONObject data, Failure error) {
      if (finishing) return;
      finishing = true;
            if (error == null && user.isEmpty() && data != null && data.optBoolean("authenticated")) {
                if (sessionManager != null) sessionManager.flush();
                InstagramSession.recordVerified(context, cookies != null);
                persistentAuthenticated = cookies == null;
            }

      result = data;
      failure = error;
      main.removeCallbacksAndMessages(token);
      log.outcome(
          error == null
              ? (user.isEmpty() ? "SESSION_VERIFIED" : "PROFILE_IMAGE_RESOLVED")
              : error.code,
          reason,
          error != null && error.http != 0 ? error.http : http,
          pageFinished,
          candidateFound,
          sessionEvidence,
          SystemClock.uptimeMillis() - began,
          user.isEmpty() ? SESSION_DEADLINE_MS : profileDeadlineMs);
      if (error != null) log.log("WEBVIEW", error.code, error.http);
      if (error != null && FailurePolicy.pause(error)) trip(error);
      try {
        if (view == null) {
          complete();
          return;
        }
        WebView reset = view;
        view = null;
        reset.stopLoading();
        reset.setWebChromeClient(null);
        reset.setDownloadListener(null);
        reset.getSettings().setJavaScriptEnabled(false);
        if (broken || cancelled || barrier != null || views.size() > ceiling) {
          destroy(reset);
          complete();
          return;
        }
        reset.setWebViewClient(
            new WebViewClient() {
              @Override
              public void onPageFinished(WebView v, String url) {
                if (!"about:blank".equals(url) || completed) return;
                reset.clearHistory();
                if (cancelled || barrier != null || views.size() > ceiling) destroy(reset);
                else idle.add(reset);
                complete();
              }

              @Override
              public WebResourceResponse shouldInterceptRequest(
                  WebView v, WebResourceRequest request) {
                return new WebResourceResponse(
                    "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
              }

              @Override
              public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                destroy(reset);
                complete();
                return true;
              }
            });
        later(
            () -> {
              if (!completed) {
                destroy(reset);
                complete();
              }
            },
            2000);
        reset.loadUrl("about:blank");
      } catch (RuntimeException | LinkageError cleanup) {
        barrier = new Failure("WEBVIEW_CLEANUP_FAILED", 0, 0, true);
        if (failure == null) failure = barrier;
        log.log("WEBVIEW", "WEBVIEW_CLEANUP_FAILED", 0);
        complete();
      }
    }

    void complete() {
      if (completed) return;
      completed = true;
      if (counted) {
        counted = false;
        QueueControl.activeViews = Math.max(0, --activeCount);
      }
      main.removeCallbacksAndMessages(token);
      log.log("WEBVIEW", "CLEANED", 0);
      done.countDown();
    }
  }
}
