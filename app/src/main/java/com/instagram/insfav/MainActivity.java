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
import org.json.*;
import com.instagram.insfav.fetcher.Fetcher;
import com.instagram.insfav.fetcher.SettingsBackupUi;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private WebView web;
    private volatile boolean foreground;
    private Store store;
    private LocalImages images;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService scans = Executors.newSingleThreadExecutor();
    private String pendingExport;
    private SettingsBackupUi settingsBackup;
    private final DoubleBackExit exitBack = new DoubleBackExit();
    private final java.util.concurrent.atomic.AtomicBoolean scanPending = new java.util.concurrent.atomic.AtomicBoolean();
    private static final int IMPORT = 20, EXPORT = 21, FOLDER = 22, WRITE_FOLDER = 23;
    private final Fetcher.Listener fetchListener=new Fetcher.Listener(){
        public void changed(){if(!foreground)return;if(!io.isShutdown())try{io.execute(()->{try{send("fetcher-status",Fetcher.snapshot());}catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}}
        public void fullReady(String user,String token,int width,int height){try{send("remote-full",new JSONObject().put("user",user).put("token",token).put("width",width).put("height",height));}catch(Exception ignored){}}
        public void fullFailed(String token,String code){try{send("remote-full-failed",new JSONObject().put("token",token).put("code",code));}catch(Exception ignored){}}
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Fetcher.addListener(fetchListener); store = Store.get(this); images = new LocalImages(this, store);
        settingsBackup=new SettingsBackupUi(this,io,new SettingsBackupUi.Host(){
            public String export(char[] pass)throws Exception {
                JSONObject extra=new JSONObject();synchronized(store){for(String k:new String[]{"treeUri","directoryId","folderLabel"})if(store.settings.has(k))extra.put(k,store.settings.getString(k));}
                return Fetcher.exportSettings(pass,extra);
            }
            public JSONObject inspect(String raw,char[] pass)throws Exception {
                JSONObject data=Fetcher.inspectSettings(raw,pass),extra=data.getJSONObject("extra");
                for(java.util.Iterator<String> i=extra.keys();i.hasNext();){String key=i.next();if(!java.util.Arrays.asList("treeUri","directoryId","folderLabel").contains(key)||!(extra.get(key) instanceof String))throw new IOException("Invalid settings.");}
                return data;
            }
            public void apply(JSONObject data)throws Exception {
                if(images.scanning()||scanPending.get())throw new IOException("Wait for the scan to finish.");
                Fetcher.restoreSettings(data);
                // SAF grants are device-owned, never restored from a JSON claim.
                JSONObject extra=data.getJSONObject("extra");String tree=extra.optString("treeUri");boolean granted=false;
                for(UriPermission grant:getContentResolver().getPersistedUriPermissions())if(grant.getUri().toString().equals(tree)&&grant.isReadPermission())granted=true;
                if(granted)synchronized(store){String old=store.settings.optString("treeUri");JSONObject before=new JSONObject(store.settings.toString());try{for(java.util.Iterator<String> i=extra.keys();i.hasNext();){String k=i.next();store.settings.put(k,extra.get(k));}store.saveSettings();if(!old.equals(tree))store.replaceIndex(new JSONObject());}catch(Exception e){store.settings=before;throw e;}}
                images.checkAccess();Fetcher.sync();send("fetcher-status",Fetcher.snapshot());send("status",images.snapshot(true));
            }
            public void message(String text){send("message",text);}
        });
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);getWindow().getInsetsController().setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());}return insets.consumeSystemWindowInsets();});
        web=new WebView(this);web.setBackgroundColor(Color.BLACK);web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);s.setCacheMode(WebSettings.LOAD_DEFAULT);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSupportZoom(false);s.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        WebView.setWebContentsDebuggingEnabled(false);web.addJavascriptInterface(new Bridge(),"Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"insfav.local".equals(uri.getHost())) return response("text/plain", new byte[0]);
                String path = uri.getPath();
                try {
                    if(path.startsWith("/remote-full/")){
                        byte[] data=Fetcher.full(uri.getLastPathSegment());
                        java.util.Map<String,String> headers=new java.util.HashMap<>();headers.put("Cache-Control","no-store, no-cache, must-revalidate");
                        return new WebResourceResponse(Fetcher.fullMime(uri.getLastPathSegment()),null,200,"OK",headers,new ByteArrayInputStream(data));
                    }
                    if (path.startsWith("/thumb/") || path.startsWith("/full/")) {
                        String user = Store.normalize(uri.getLastPathSegment());
                        if (!user.matches("[a-z0-9._]{1,30}")) return response("image/webp", new byte[0]);
                        if(path.startsWith("/full/"))return response("image/png",images.image(user,true));
                        java.util.Map<String,String> headers=new java.util.HashMap<>();
                        headers.put("Cache-Control","private, max-age=300");
                        return new WebResourceResponse(images.mime(user),null,200,"OK",headers,new ByteArrayInputStream(images.image(user,false)));
                    }
                    String name = "/".equals(path) ? "index.html" : path.substring(1);
                    if (!name.matches("[a-zA-Z0-9._-]+")) return response("text/plain", new byte[0]);
                    String mime = name.endsWith(".woff2") ? "font/woff2" : name.endsWith(".webp") ? "image/webp" : name.endsWith(".js") ? "application/javascript" : name.endsWith(".css") ? "text/css" : "text/html";
                    return new WebResourceResponse(mime,"UTF-8",200,"OK",java.util.Collections.singletonMap("Cache-Control","no-store"),getAssets().open(name));
                } catch (Exception error) {store.log("local_resource", "READ_ERROR"); return response("text/plain", new byte[0]);}
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {return true;}
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                store.log("web_renderer", "stopped"); root.removeView(view); web = null; view.destroy(); recreate(); return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1)); setContentView(root); root.requestApplyInsets();
        web.loadUrl("https://insfav.local/index.html");
    }
    @Override protected void onResume() {
        super.onResume();foreground=true;
        io.execute(() -> {images.checkAccess(); try {send("status", images.snapshot(true));} catch (Exception error) {error(error);}});
    }
    private WebResourceResponse response(String mime, byte[] bytes) {return new WebResourceResponse(mime,null,200,"OK",java.util.Collections.singletonMap("Cache-Control","no-store"),new ByteArrayInputStream(bytes));}
    private void send(String event, Object data) {
        runOnUiThread(() -> {
            if (web == null || isDestroyed()) return;
            String value = data instanceof JSONObject ? data.toString() : JSONObject.quote(String.valueOf(data));
            web.evaluateJavascript("window.nativeEvent&&window.nativeEvent(" + JSONObject.quote(event) + "," + value + ")", null);
        });
    }
    private void error(Exception error) {store.log("operation_error", error.getClass().getSimpleName()); send("error", error.getMessage() == null ? "Operation failed" : error.getMessage());}
    private JSONObject appInfo() throws Exception {
        PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
        return new JSONObject().put("version", info.versionName).put("build", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
    }
    public final class Bridge {
        @JavascriptInterface public void request(String raw) {
            if (io.isShutdown()) return;
            try {io.execute(() -> {
                try {
                    JSONObject input = new JSONObject(raw); String command = input.getString("command");
                    switch (command) {
                        case "bootstrap": {
                            images.checkAccess(); JSONObject result = images.snapshot(true);
                            result.put("raw", store.original()).put("app", appInfo()).put("stats", new JSONObject().put("usage", incrementUsage()).put("load", getPreferences(0).getInt("load", 0)).put("clear", getPreferences(0).getInt("clear", 0)));
                            send("bootstrap", result); break;
                        }
                        case "status": send("status", images.snapshot(true)); break;
                        case "copy-missing-users": {
                            String text=images.missingUsernames();
                            runOnUiThread(()->{try{int count=MissingProfiles.copy(MainActivity.this,text);send("message",count==0?"No missing usernames.":count+" usernames copied.");}catch(RuntimeException e){send("error","Clipboard unavailable. Try again while INSFAV is open.");}});
                            break;
                        }
                        case "resolution-stats": send("resolution-stats",Fetcher.resolution(-1));break;
                        case "resolution-details": send("resolution-details",Fetcher.resolution(input.optInt("offset",0)));break;
                        case "fetcher-status": if(!Fetcher.running())Fetcher.sync();send("fetcher-status",Fetcher.snapshot());break;
                        case "fetcher-paste": {
                            android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);
                            android.content.ClipData clip=clipboard.getPrimaryClip();CharSequence text=clip==null||clip.getItemCount()==0?null:clip.getItemAt(0).getText();
                            if(text==null)throw new IOException("No plain text is available in the clipboard.");if(text.length()>131072)throw new IOException("Cookie text is too large.");
                            send("cookie-paste",text.toString());break;
                        }
                        case "fetcher-cookies-save": Fetcher.saveCookies(input.getString("text"));send("fetcher-status",Fetcher.snapshot());send("message","Cookies saved securely.");break;
                        case "fetcher-cookies-remove": Fetcher.removeCookies();send("fetcher-status",Fetcher.snapshot());break;
                        case "fetcher-config": Fetcher.configure(input.getInt("minimum"),input.getInt("maximum"),input.getString("start"),input.getString("end"),input.optBoolean("background"),input.optInt("concurrentWebViews",1));send("fetcher-status",Fetcher.snapshot());break;
                        case "fetcher-action": if(images.scanning())throw new IOException("Wait for the thumbnail scan to finish.");runOnUiThread(()->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},24);});Fetcher.action(input.getString("action"));break;
                        case "fetcher-write-access": grantFetcherWrite();break;
                        case "fetcher-logs": export(Fetcher.diagnostics(),"INSFAV-fetcher-diagnostics.txt","text/plain");break;
                        case "view-remote": Fetcher.view(Store.normalize(input.getString("user")),input.getString("token"));break;
                        case "view-closed": Fetcher.closeViewer();runOnUiThread(()->{if(web!=null)web.clearCache(true);});break;
                        case "select-folder": selectFolder(); break;
                        case "rescan": if(Fetcher.running())throw new IOException("Pause the fetcher before rescanning.");
                            startScan(false);break;
                        case "clear-cache": runOnUiThread(()->{if(web!=null)web.clearCache(true);}); images.clearCache(); images.clearLegacyCache(); send("image-cache-cleared",new JSONObject()); send("status", images.snapshot(true)); send("message", "Image cache cleared. Source photos and collections are unchanged."); break;
                        case "clear-index": if(Fetcher.running())throw new IOException("Pause the fetcher before clearing its image index.");images.clearIndex(); send("status", images.snapshot(true)); send("message", "Thumbnail index cleared. Source photos and collections are unchanged."); break;
                        case "image-failed": send("status", images.snapshot(true)); break;
                        case "import": runOnUiThread(() -> {
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"}); startActivityForResult(intent, IMPORT);
                        }); break;
                        case "export": if (store.original().isEmpty()) throw new IOException("No collections to export"); export(store.original(), "INSFAV-backup.json", "application/json"); break;
                        case "settings-export": settingsBackup.start(true); break;
                        case "settings-import": settingsBackup.start(false); break;
                        case "logs": {
                            JSONObject diagnostic=images.snapshot(false);
                            export(store.logs()+"\nStorage bytes: "+images.storageAudit()+"\nImage I/O: "+diagnostic.optJSONObject("imageIO")
                                +"\nScan I/O: "+diagnostic.getJSONObject("scan").optJSONObject("io"),"INSFAV-diagnostics.txt","text/plain");break;
                        }
                        case "frontend-error": store.log("web_error", input.optString("code", "script")); break;
                        case "clear-data": if(images.scanning()||scanPending.get())throw new IOException("Wait for the scan to finish.");if(Fetcher.running())throw new IOException("Pause the fetcher before clearing collections.");store.clearDocument(); getPreferences(0).edit().putInt("clear", getPreferences(0).getInt("clear", 0) + 1).apply(); send("cleared", ""); break;
                        case "open": openInstagram(input.getString("user")); break;
                        default: throw new IOException("Unsupported operation");
                    }
                } catch (Exception error) {error(error);}
            });} catch (RejectedExecutionException ignored) { }
        }
    }
    private int incrementUsage() {int value = getPreferences(0).getInt("usage", 0) + 1; getPreferences(0).edit().putInt("usage", value).apply(); return value;}
    private void selectFolder() {
        if (Fetcher.running()) {send("error","Pause the fetcher before changing folders.");return;}
        if (images.scanning() || scanPending.get()) {send("error", "Wait for the scan to finish."); return;}
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            String current; synchronized (store) {current = store.settings.optString("treeUri");}
            if (!current.isEmpty()) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(current));
            startActivityForResult(intent, FOLDER);
        });
    }
    private void grantFetcherWrite(){
        String current; synchronized(store){current=store.settings.optString("treeUri");}
        if(current.isEmpty()){send("error","Select a thumbnail folder in Profile Images first.");return;}
        runOnUiThread(()->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(current));startActivityForResult(intent,WRITE_FOLDER);
        });
    }
    private void startScan(boolean cleanupRequested) {
        if (!scanPending.compareAndSet(false, true)) {send("message", "A scan is already in progress."); return;}
        scans.execute(() -> {try {images.scan((event, result) -> send(event, result),cleanupRequested);} finally {scanPending.set(false);}});
    }
    private void openInstagram(String name) {
        String user = Store.normalize(name);
        if (!user.matches("[a-z0-9._]{1,30}")) {send("error", "Invalid Instagram username"); return;}
        runOnUiThread(() -> {
            try {startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("instagram://user?username=" + Uri.encode(user))));}
            catch (ActivityNotFoundException error) {
                try {startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/" + user + "/")));}
                catch (ActivityNotFoundException missing) {send("error", "No app can open this profile.");}
            }
        });
    }
    private void export(String content, String name, String mime) {
        if (pendingExport != null) {send("error", "Finish the current export first."); return;}
        pendingExport = content;
        runOnUiThread(() -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), EXPORT));
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if(settingsBackup.result(request,result,intent))return;
        if (result != RESULT_OK || intent == null || intent.getData() == null) {if (request == EXPORT) pendingExport = null; return;}
        Uri uri = intent.getData(); int flags = intent.getFlags();
        io.execute(() -> {
            try {
                if(request==WRITE_FOLDER){
                    String current; synchronized(store){current=store.settings.optString("treeUri");}
                    if(!uri.toString().equals(current))throw new IOException("Select the same INSFAV thumbnail folder. The existing folder and index were preserved.");
                    int grant=flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if(grant!=(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))throw new IOException("Folder read/write access was not granted.");
                    getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);Fetcher.sync();send("fetcher-status",Fetcher.snapshot());
                }else if (request == FOLDER) {images.select(uri, flags); send("status", images.snapshot(true)); startScan(false);}
                else if (request == IMPORT) {
                    String raw;
                    try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        if (input == null) throw new IOException("Document cannot be opened"); byte[] bytes = new byte[8192]; int length;
                        while ((length = input.read(bytes)) != -1) {if (output.size() + length > 32 * 1024 * 1024) throw new IOException("JSON exceeds 32 MiB"); output.write(bytes, 0, length);}
                        raw = output.toString("UTF-8");
                    }
                    if(Fetcher.running()||images.scanning()||scanPending.get())throw new IOException("Pause the fetcher and wait for scans before importing collections.");
                    store.importDocument(raw); getPreferences(0).edit().putInt("load", getPreferences(0).getInt("load", 0) + 1).apply();
                    JSONObject snapshot = images.snapshot(true); snapshot.put("raw", raw); send("imported", snapshot);
                } else if (request == EXPORT && pendingExport != null) {
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {if (output == null) throw new IOException("Destination cannot be opened"); output.write(pendingExport.getBytes(StandardCharsets.UTF_8));}
                    pendingExport = null; store.log("export", "success"); send("message", "Export complete.");
                }
            } catch (Exception error) {if (request == EXPORT) pendingExport = null; error(error);}
        });
    }
    @Override public void onBackPressed() {if (web == null) {requestExit(); return;} web.evaluateJavascript("window.handleBack&&window.handleBack()", value -> {if ("true".equals(value)) exitBack.reset(); else requestExit();});}
    private void requestExit() {
        if (!foreground || isFinishing() || isDestroyed()) return;
        if (exitBack.press(SystemClock.elapsedRealtime())) super.onBackPressed();
        else if (web != null) send("message", "Press back again to exit.");
        else android.widget.Toast.makeText(this, "Press back again to exit.", android.widget.Toast.LENGTH_SHORT).show();
    }
    @Override protected void onStop(){exitBack.reset();foreground=false;Fetcher.foregroundStopped();super.onStop();}
    @Override protected void onDestroy() {
        Fetcher.removeListener(fetchListener);Fetcher.closeViewer();
        images.cancelScan(); scans.shutdownNow(); io.shutdownNow();
        if (web != null) {web.removeJavascriptInterface("Android"); web.destroy(); web = null;} super.onDestroy();
    }
}
