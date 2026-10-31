package com.instagram.insfav.fetcher;

import android.annotation.SuppressLint;
import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.webkit.*;
import android.view.*;
import android.widget.*;

/** Visible persistent Instagram browser. Cookie details stay inside this secure Activity. */
public final class InstagramLoginActivity extends Activity {
    private WebView browser;
    private TextView status, cookies, savedStatus;
    private Dialog details;
    private Button saveButton;
    private boolean lease, destroyed, savedTab, turkish, foreground;
    private String engine;
    private int detailGeneration;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable capturePage = () -> { if (browser != null && safePage()) BrowserCookieStore.capture(this,engine,false,null); };
    private String t(String en, String tr) { return turkish ? tr : en; }
    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    @Override @SuppressLint("SetJavaScriptEnabled") public void onCreate(Bundle saved) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(saved);
        String language = getSharedPreferences(getPackageName().endsWith(".plus") ? "plus-ui" : "insfav-ui",0).getString("language",getPackageName().endsWith(".plus") ? "system" : "en");
        turkish = language.equals("tr") || language.equals("system") && getResources().getConfiguration().getLocales().get(0).getLanguage().equals("tr");
        engine = InstagramSession.engine(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        LinearLayout layout = column(); layout.setBackgroundColor(Color.BLACK);
        LinearLayout header = row(); header.setPadding(dp(16),dp(10),dp(16),dp(4));
        TextView title = text("Instagram",22,Color.WHITE); title.setTypeface(null,Typeface.BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        button(header,t("Done","Bitti"),this::finish); layout.addView(header);
        TextView method = text(engine.equals("netscape") ? "YTDLnis · Netscape" : "Legacy · WebView",12,0xff999999);
        method.setPadding(dp(18),0,dp(18),dp(6)); layout.addView(method);
        LinearLayout actions = row(); actions.setPadding(dp(12),0,dp(12),dp(4));
        button(actions,t("Refresh","Yenile"),this::refreshPage);
        button(actions,t("Cookies","Çerezler"),this::showCookies);
        button(actions,t("Log out","Çıkış yap"),this::confirmLogout);
        layout.addView(actions);
        status = text(t("Your existing Instagram session is preserved.","Mevcut Instagram oturumunuz korunur."),12,0xffaaaaaa);
        status.setPadding(dp(18),dp(4),dp(18),dp(10)); status.setMaxLines(3);layout.addView(status);
        setContentView(layout);
        if (QueueControl.lock.get() || !WebViewProfileProvider.SESSION_LEASE.tryAcquire()) {
            status.setText(t("Pause retrieval and close the HD viewer before opening Instagram.","Instagram'ı açmak için indirmeyi duraklatın ve HD görüntüleyiciyi kapatın.")); return;
        }
        lease = true;
        try {
            WebView.setWebContentsDebuggingEnabled(false);
            browser = new WebView(this); browser.setBackgroundColor(Color.BLACK);
            layout.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
            WebSettings settings = browser.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setGeolocationEnabled(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
            ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(false);
            CookieManager manager = CookieManager.getInstance(); manager.setAcceptCookie(true);manager.setAcceptThirdPartyCookies(browser,false);
            browser.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onConsoleMessage(ConsoleMessage message) { return true; }
                @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
                @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback cb) { cb.invoke(origin,false,false); }
            });
            browser.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) { return request.isForMainFrame() && !instagram(request.getUrl()); }
                @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon) {
                    main.removeCallbacks(capturePage);
                    if (!"about:blank".equals(url) && !instagram(Uri.parse(url))) {view.stopLoading();return;}
                    String path = Uri.parse(url).getPath();
                    if (challenge(path)) {InstagramSession.blocked(InstagramLoginActivity.this,false,"CHALLENGE_REQUIRED");status.setText(t("Complete Instagram verification here, then save cookies.","Instagram doğrulamasını burada tamamlayın, ardından çerezleri kaydedin."));}
                }
                @Override public void onPageFinished(WebView view,String url) {
                    InstagramSession.flush();refreshCookies();main.removeCallbacks(capturePage);main.postDelayed(capturePage,300);
                }
                @Override public void onReceivedSslError(WebView view,SslErrorHandler h,SslError e) {h.cancel();status.setText(t("Instagram connection could not be verified.","Instagram bağlantısı doğrulanamadı."));}
                @Override public void onReceivedError(WebView view,WebResourceRequest r,WebResourceError e) {if(r.isForMainFrame())status.setText(t("Page could not load. Check your connection and refresh.","Sayfa yüklenemedi. Bağlantınızı kontrol edip yenileyin."));}
                @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail) {browser=null;layout.removeView(view);view.destroy();status.setText(t("Browser stopped. Reopen Instagram to continue.","Tarayıcı durdu. Devam etmek için Instagram ekranını yeniden açın."));return true;}
            });
            browser.loadUrl(InstagramSession.ORIGIN);
        } catch (RuntimeException | LinkageError error) {status.setText(t("Instagram WebView unavailable.","Instagram WebView kullanılamıyor."));}
    }
    private static boolean challenge(String path) {return path!=null&&(path.contains("challenge")||path.contains("checkpoint")||path.contains("two_factor"));}
    private boolean safePage() {return browser!=null&&browser.getUrl()!=null&&instagram(Uri.parse(browser.getUrl()))&&!challenge(Uri.parse(browser.getUrl()).getPath());}
    private void showCookies() {
        if (details != null && details.isShowing()) return;
        savedTab = true;details = new Dialog(this);LinearLayout panel = column();panel.setPadding(dp(18),dp(20),dp(18),dp(16));panel.setBackground(background(0xff080808,24));
        TextView heading = text(t("Instagram cookies","Instagram çerezleri"),20,Color.WHITE);heading.setTypeface(null,Typeface.BOLD);panel.addView(heading);
        TextView note = text(t("Saved cookies update after HD browsing. No session test is required. Removing a saved copy keeps Instagram signed in.","Kaydedilen çerezler HD kullanımı sırasında yenilenir. Oturum testi gerekmez. Kayıt silindiğinde Instagram oturumu açık kalır."),12,0xff999999);
        note.setPadding(0,dp(8),0,dp(8));panel.addView(note);
        LinearLayout tabs=row();button(tabs,t("Live WebView","Canlı WebView"),()->{savedTab=false;refreshCookies();});button(tabs,t("Saved Netscape","Kaydedilen"),()->{savedTab=true;refreshCookies();});panel.addView(tabs);
        savedStatus=text("",12,0xffaaaaaa);savedStatus.setPadding(0,dp(8),0,dp(8));panel.addView(savedStatus);
        cookies=text("",12,0xffdddddd);cookies.setTypeface(Typeface.MONOSPACE);cookies.setTextIsSelectable(true);cookies.setSaveEnabled(false);cookies.setFreezesText(false);cookies.setPadding(dp(12),dp(12),dp(12),dp(12));cookies.setBackground(background(Color.BLACK,14));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(cookies);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=row();saveButton=button(controls,t("Save cookies","Çerezleri kaydet"),this::extractSession);button(controls,t("Remove saved","Kaydı sil"),this::confirmRemove);panel.addView(controls);
        button(panel,t("Close","Kapat"),()->details.dismiss());details.setContentView(panel);
        details.setOnDismissListener(d->{detailGeneration++;if(cookies!=null)cookies.setText("");cookies=null;savedStatus=null;saveButton=null;});
        Window window=details.getWindow();if(window!=null){window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);window.setBackgroundDrawableResource(android.R.color.transparent);}
        details.show();window=details.getWindow();if(window!=null){window.setGravity(Gravity.BOTTOM);window.setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*.72f));window.setNavigationBarColor(Color.BLACK);}
        refreshCookies();
    }
    private void extractSession() {
        if (!safePage()) {status.setText(t("Complete Instagram verification before saving.","Kaydetmeden önce Instagram doğrulamasını tamamlayın."));return;}
        if(saveButton!=null)saveButton.setEnabled(false);
        status.setText(t("Reading and saving WebView cookies…","WebView çerezleri okunup kaydediliyor…"));
        BrowserCookieStore.capture(this,engine,true,(jar,error)->{
            if(destroyed)return;if(saveButton!=null)saveButton.setEnabled(true);
            if(!error.isEmpty()){status.setText(extractionError(error));if(savedStatus!=null)savedStatus.setText(extractionError(error));return;}
            if(!safePage()){status.setText(t("Complete Instagram verification before continuing.","Devam etmeden önce Instagram doğrulamasını tamamlayın."));return;}
            SessionState.changed(this);
            SessionState.prefs(this).edit().putBoolean("manualSession",false).putBoolean("webSessionHold",false).putBoolean("instagramSecurityHold",false)
                .putString("webSessionStatus","Cookies saved · checked on request").putBoolean("armed",false).putBoolean("userPaused",true).commit();
            status.setText(t("Cookies saved securely. HD is ready to request; bulk stays paused.","Çerezler güvenle kaydedildi. HD kullanılabilir; toplu indirme duraklatıldı."));savedTab=true;refreshCookies();
        });
    }
    private String extractionError(String code) {
        if(code.equals("COOKIE_STORAGE_FAILED"))return t("Cookies were read but could not be saved securely.","Çerezler okundu ancak güvenle kaydedilemedi.");
        if(code.equals("REQUEST_CANCELLED"))return t("Session changed. Try saving again.","Oturum değişti. Yeniden kaydedin.");
        if(code.equals("COOKIE_INVALID")||code.equals("COOKIE_EXPIRED"))return t("No usable session cookie. Sign in inside Instagram first.","Kullanılabilir oturum çerezi yok. Önce Instagram içinde giriş yapın.");
        return t("WebView cookies could not be read. Refresh and try again.","WebView çerezleri okunamadı. Sayfayı yenileyip tekrar deneyin.");
    }
    private void refreshCookies() {
        if(!foreground||cookies==null||savedStatus==null)return;
        int generation=++detailGeneration;
        if(!savedTab){String header;try{header=CookieManager.getInstance().getCookie(InstagramSession.ORIGIN);}catch(RuntimeException e){header=null;}
            savedStatus.setText(t("Live WebView · instagram.com","Canlı WebView · instagram.com"));cookies.setText(header==null||header.isEmpty()?t("No Instagram cookies yet.","Henüz Instagram çerezi yok."):header);return;}
        cookies.setText("");savedStatus.setText(t("Reading encrypted saved copy…","Şifreli kayıt okunuyor…"));
        String revision=BrowserCookieStore.revision(this,engine);
        BrowserCookieStore.saved(this,engine,(jar,error)->{
            if(destroyed||!foreground||cookies==null||!savedTab||generation!=detailGeneration||!revision.equals(BrowserCookieStore.revision(this,engine)))return;
            if(!error.isEmpty()){savedStatus.setText(t("No readable saved copy. Live WebView is independent.","Okunabilir kayıt yok. Canlı WebView oturumu bundan bağımsızdır."));cookies.setText("");return;}
            long at=SessionState.prefs(this).getLong("cookieSnapshotSavedAt_"+engine,0);
            savedStatus.setText(t("Saved copy · ","Kaydedilen kopya · ")+engine+(at==0?"":" · "+android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(at))));
            cookies.setText(NetscapeBrowserCookies.text(jar));
        });
    }
    private void confirmRemove() {
        confirm(t("Remove saved cookies?","Kaydedilen çerezler silinsin mi?"),t("Only this method's extracted copy is removed. The other method, manual cookies and live Instagram login are preserved. Save cookies again to enable automatic updates.","Yalnızca bu yöntemin çıkarılmış kaydı silinir. Diğer yöntem, manuel çerezler ve canlı Instagram oturumu korunur. Otomatik yenilemeyi açmak için yeniden kaydedin."),()->{
            BrowserCookieStore.remove(this,engine);detailGeneration++;refreshCookies();status.setText(t("Saved copy removed. Instagram is still signed in.","Kaydedilen kopya silindi. Instagram oturumu açık kaldı."));
        });
    }
    private void confirmLogout() {
        confirm(t("Log out of Instagram?","Instagram'dan çıkış yapılsın mı?"),t("Clears this app's Instagram login and both extracted browser copies. Manual cookies and your images stay unchanged.","Bu uygulamanın Instagram oturumunu ve iki çıkarılmış tarayıcı kaydını temizler. Manuel çerezler ve görseller korunur."),()->{
            if(browser==null)return;main.removeCallbacks(capturePage);browser.stopLoading();browser.loadUrl("about:blank");
            InstagramSession.logout(this,()->{refreshCookies();status.setText(t("Signed out","Çıkış yapıldı"));if(browser!=null)browser.loadUrl(InstagramSession.ORIGIN+"accounts/login/");});
        });
    }
    private void confirm(String title,String message,Runnable action) {
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton(t("Cancel","Vazgeç"),null).setPositiveButton(t("Confirm","Onayla"),(d,w)->action.run()).create();
        dialog.show();if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawable(background(0xff101010,22));dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    }
    private void refreshPage(){if(browser!=null){InstagramSession.flush();refreshCookies();browser.reload();}}
    static boolean instagram(Uri uri){return "https".equals(uri.getScheme())&&(uri.getPort()==-1||uri.getPort()==443)&&uri.getUserInfo()==null&&("www.instagram.com".equals(uri.getHost())||"instagram.com".equals(uri.getHost()));}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private GradientDrawable background(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));g.setStroke(dp(1),0xff242424);return g;}
    private Button button(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setTextIsSelectable(false);b.setLongClickable(false);b.setText(label);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(Color.WHITE);b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));b.setPadding(dp(10),0,dp(10),0);b.setBackground(background(0xff101010,16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(row.getOrientation()==LinearLayout.VERTICAL?-1:0,dp(44),row.getOrientation()==LinearLayout.VERTICAL?0:1);p.setMargins(dp(4),dp(4),dp(4),dp(4));row.addView(b,p);b.setOnClickListener(v->action.run());return b;}
    @Override public void onBackPressed(){if(details!=null&&details.isShowing())details.dismiss();else if(browser!=null&&browser.canGoBack())browser.goBack();else super.onBackPressed();}
    @Override protected void onResume(){super.onResume();foreground=true;refreshCookies();}
    @Override protected void onPause(){foreground=false;detailGeneration++;if(cookies!=null)cookies.setText("");InstagramSession.flush();super.onPause();}
    @Override protected void onStop(){InstagramSession.flush();super.onStop();}
    @Override protected void onDestroy(){destroyed=true;main.removeCallbacks(capturePage);if(details!=null)details.dismiss();if(browser!=null){browser.stopLoading();browser.destroy();browser=null;}InstagramSession.flush();if(lease){lease=false;WebViewProfileProvider.SESSION_LEASE.release();}super.onDestroy();}
}
