package com.instagram.insfav.fetcher;

import android.annotation.SuppressLint;
import android.app.*;
import android.os.*;
import android.net.Uri;
import android.net.http.SslError;
import android.webkit.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import org.json.*;

/** Visible first-party login, including user-completed 2FA/checkpoints. No JavaScript bridge. */
public final class InstagramLoginActivity extends Activity {
    private WebView browser;
    private TextView status, cookies;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean lease, resumed, verified, probeRunning;
    private String probe = "";
    private long checkingSince, documentGeneration;
    private final Runnable poll = this::check;
    @Override @SuppressLint("SetJavaScriptEnabled") public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this); status.setText("Instagram · checking session"); layout.addView(status);
        LinearLayout controls = new LinearLayout(this); layout.addView(controls);
        button(controls, "Refresh", this::refreshPage);
        button(controls, "Log out", () -> {
            if (browser == null) return;
            browser.stopLoading(); browser.loadUrl("about:blank"); handler.removeCallbacks(poll);
            InstagramSession.logout(this, () -> { verified = false; refreshCookies(); status.setText("Signed out"); browser.loadUrl(InstagramSession.ORIGIN + "accounts/login/"); });
        });
        button(controls, "Done", this::finish);
        LinearLayout extraction = new LinearLayout(this); layout.addView(extraction);
        button(extraction, "Get cookies for downloads", () -> extractSession(true));
        TextView cookieTitle = new TextView(this); cookieTitle.setText("WebView cookies · instagram.com"); layout.addView(cookieTitle);
        cookies = new TextView(this); cookies.setTextIsSelectable(true); cookies.setSaveEnabled(false); cookies.setFreezesText(false);
        ScrollView cookieScroll = new ScrollView(this); cookieScroll.addView(cookies);
        layout.addView(cookieScroll, new LinearLayout.LayoutParams(-1, (int)(120 * getResources().getDisplayMetrics().density)));
        refreshCookies();
        setContentView(layout);
        if (QueueControl.lock.get() || !WebViewProfileProvider.SESSION_LEASE.tryAcquire()) {
            status.setText("Pause retrieval and close the image viewer before opening Instagram."); return;
        }
        lease = true;
        try {
            try (InputStream in = getAssets().open("profile-probe.js"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) { if (out.size() + n > 32768) throw new IOException(); out.write(buffer, 0, n); }
                probe = out.toString("UTF-8");
            }
            WebView.setWebContentsDebuggingEnabled(false);
            browser = new WebView(this); layout.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
            WebSettings settings = browser.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setGeolocationEnabled(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
            CookieManager manager = CookieManager.getInstance(); manager.setAcceptCookie(true); manager.setAcceptThirdPartyCookies(browser, false);
            browser.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onConsoleMessage(ConsoleMessage message) { return true; }
                @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
                @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) { callback.invoke(origin, false, false); }
            });
            browser.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return request.isForMainFrame() && !instagram(request.getUrl());
                }
                @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                    documentGeneration++; probeRunning = false; verified = false; checkingSince = android.os.SystemClock.uptimeMillis();
                    if (!"about:blank".equals(url) && !instagram(Uri.parse(url))) { view.stopLoading(); return; }
                    String path = Uri.parse(url).getPath();
                    if (path != null && (path.contains("challenge") || path.contains("checkpoint") || path.contains("two_factor")))
                        blocked("CHALLENGE_REQUIRED");
                }
                @Override public void onPageFinished(WebView view, String url) { InstagramSession.flush(); refreshCookies(); extractSession(false); check(); }
                @Override public void onReceivedSslError(WebView view, SslErrorHandler h, SslError error) { h.cancel(); status.setText("Instagram connection could not be verified"); }
                @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) status.setText("Unable to check session; check your connection");
                }
                @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    browser = null; layout.removeView(view); view.destroy(); status.setText("Browser stopped. Reopen Instagram to continue."); return true;
                }
            });
            // A restored session opens the signed-in home, never an unsolicited fresh login.
            browser.loadUrl(InstagramSession.ORIGIN);
        } catch (IOException | RuntimeException | LinkageError error) { status.setText("Instagram WebView unavailable"); }
    }
    private void extractSession(boolean explicit) {
        if(browser==null)return;
        String url=browser.getUrl();
        if(url==null||!instagram(Uri.parse(url)))return;
        String path=Uri.parse(url).getPath();
        if(path!=null&&(path.contains("challenge")||path.contains("checkpoint")||path.contains("two_factor"))){if(explicit)status.setText("Complete Instagram verification first.");return;}
        new Thread(()->{
            try {
                BrowserCookies.capture(this);
                if(explicit) {
                    SessionState.changed(this);
                    SessionState.prefs(this).edit().putBoolean("manualSession",false).putBoolean("webSessionHold",false)
                        .putBoolean("instagramSecurityHold",false).putBoolean("armed",false).putBoolean("userPaused",true)
                        .putString("webSessionStatus","Cookies captured · checked on request").putString("blocked","").commit();
                    runOnUiThread(()->{refreshCookies();status.setText("WebView cookies saved securely for HD and downloads. Bulk stays paused.");});
                }
            }catch(ProfileProvider.Failure e){if(explicit)runOnUiThread(()->status.setText("Sign in above first; no usable Instagram session cookies were extracted."));}
        },"instagram-cookie-capture").start();
    }
    private void refreshPage() {
        if (browser == null) return;
        InstagramSession.flush(); refreshCookies(); verified = false; checkingSince = android.os.SystemClock.uptimeMillis();
        browser.reload();
    }
    private void refreshCookies() {
        if (cookies == null) return;
        String header;
        try { header = CookieManager.getInstance().getCookie(InstagramSession.ORIGIN); }
        catch (RuntimeException ignored) { header = null; }
        cookies.setText(header == null || header.isEmpty() ? "No Instagram cookies available in this WebView." : header);
    }
    static boolean instagram(Uri uri) {
        return "https".equals(uri.getScheme()) && (uri.getPort() == -1 || uri.getPort() == 443)
            && uri.getUserInfo() == null && ("www.instagram.com".equals(uri.getHost()) || "instagram.com".equals(uri.getHost()));
    }
    private void button(LinearLayout row, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); row.addView(button, new LinearLayout.LayoutParams(0, -2, 1)); button.setOnClickListener(v -> action.run());
    }
    private void blocked(String code) {
        InstagramSession.blocked(this, false, code);
        status.setText((code.equals("CHALLENGE_REQUIRED") || code.equals("ACCESS_BLOCKED")) ? "Complete Instagram verification below, then continue below" : "Sign in below, then continue below");
    }
    private void check() {
        handler.removeCallbacks(poll);
        refreshCookies();
        if (!resumed || browser == null || probeRunning || verified || !instagram(Uri.parse(browser.getUrl() == null ? "" : browser.getUrl()))) return;
        probeRunning = true;
        if (checkingSince == 0) checkingSince = android.os.SystemClock.uptimeMillis();
        if (android.os.SystemClock.uptimeMillis() - checkingSince > 12000) status.setText("Session not verified. Check your connection, then tap Refresh.");
        String id = InstagramSession.account(CookieManager.getInstance());
        final long document = documentGeneration;
        browser.evaluateJavascript("(" + probe + ")(" + JSONObject.quote("") + "," + JSONObject.quote(id) + ",false)", encoded -> {
            if (document != documentGeneration) return;
            probeRunning = false;
            if (browser == null || !resumed) return;
            try {
                JSONObject data = WebProfilePolicy.snapshot(encoded);
                String wall = data.optString("wall");
                if (wall.equals("CHALLENGE_REQUIRED") || wall.equals("ACCESS_BLOCKED") || wall.equals("COOKIE_INVALID_EXPIRED") || wall.equals("LOGIN_REQUIRED")) blocked(wall);
                else if (wall.equals("RATE_LIMITED")) status.setText("Instagram restricted requests. Wait before refreshing again.");
                else if (data.optBoolean("authenticated") && wall.isEmpty()) {
                    InstagramSession.verified(this, false); verified = true;
                    status.setText(InstagramSession.manual(this) ? "WebView session verified. Select Persistent WebView Session to use it." : "Session verified. HD does not require starting bulk retrieval; tap Done."); return;
                }
            } catch (ProfileProvider.Failure ignored) { }
            if (resumed) handler.postDelayed(poll, 1000);
        });
    }
    @Override protected void onResume() { super.onResume(); resumed = true; refreshCookies(); check(); }
    @Override protected void onPause() { resumed = false; documentGeneration++; probeRunning = false; if (cookies != null) cookies.setText(""); handler.removeCallbacks(poll); InstagramSession.flush(); super.onPause(); }
    @Override protected void onStop() { InstagramSession.flush(); super.onStop(); }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (cookies != null) cookies.setText("");
        if (browser != null) { browser.stopLoading(); browser.destroy(); browser = null; }
        InstagramSession.flush();
        if (lease) { lease = false; WebViewProfileProvider.SESSION_LEASE.release(); }
        super.onDestroy();
    }
}
