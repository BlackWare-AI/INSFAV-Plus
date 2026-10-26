package com.instagram.insfav.fetcher;
import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Synthetic offline browser data only; never makes an Instagram request. */
final class SessionInstrumentation {
    static void run(Instrumentation host, Bundle arguments) {
        Bundle result=new Bundle();
        try {
            Context c=host.getTargetContext(); boolean seed="seed".equals(arguments.getString("scenario"));
            String value="OFFLINE_"+c.getPackageName().replace('.','_');
            if ("logout".equals(arguments.getString("scenario"))) {
                CountDownLatch loggedOut=new CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<Throwable> logoutFailure=new java.util.concurrent.atomic.AtomicReference<>();
                host.runOnMainSync(() -> {
                    CookieManager manager=CookieManager.getInstance();
                    manager.setCookie("https://session-fixture.invalid/","local=kept; Max-Age=604800; Secure",accepted -> {
                        if(!Boolean.TRUE.equals(accepted)) { logoutFailure.set(new AssertionError("Unrelated origin fixture rejected"));loggedOut.countDown();return; }
                        manager.flush();
                        InstagramSession.logout(c, () -> {
                            try {
                                String web=manager.getCookie(InstagramSession.ORIGIN);
                                if(web!=null&&web.contains("sessionid="))throw new AssertionError("Instagram logout retained browser credentials");
                                String manual=InstagramSession.manager(true).getCookie(InstagramSession.ORIGIN);
                                if(manual==null||!manual.contains("sessionid=MANUAL_"+value))throw new AssertionError("Logout erased manual credentials");
                                String other=manager.getCookie("https://session-fixture.invalid/");
                                if(other==null||!other.contains("local=kept"))throw new AssertionError("Logout erased an unrelated origin");
                            }catch(Throwable error){logoutFailure.set(error);}
                            loggedOut.countDown();
                        });
                    });
                });
                if(!loggedOut.await(20,TimeUnit.SECONDS)||logoutFailure.get()!=null)throw new AssertionError("Scoped logout failed");
                java.io.File preserved=new java.io.File(c.getFilesDir(),"session-thumbnail-sentinel.webp");
                if(!"PRESERVED_THUMBNAIL".equals(new String(Files.readAllBytes(preserved.toPath()),StandardCharsets.UTF_8)))throw new AssertionError("Logout changed thumbnail sentinel");
                if(!"PENDING".equals(SessionState.prefs(c).getString("sessionQueueSentinel","")))throw new AssertionError("Logout changed queue sentinel");
                result.putString("stream","OFFLINE_SESSION_LOGOUT_SCOPED\n");host.finish(Activity.RESULT_OK,result);return;
            }

            CountDownLatch done=new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            host.runOnMainSync(() -> {
                try {
                    CookieManager m=CookieManager.getInstance(); m.setAcceptCookie(true);
                    WebView web=new WebView(c); web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
                    // No network is allowed in this instrumentation.
                    web.setWebViewClient(new WebViewClient(){
                        @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest r) {
                            return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));
                        }
                        @Override public void onPageFinished(WebView view,String url) {
                            if (!InstagramSession.ORIGIN.equals(url)) return;
                            if(seed) {
                                web.evaluateJavascript("localStorage.setItem('session-fixture','offline');true", ignored -> {
                                    m.setCookie(InstagramSession.ORIGIN,"sessionid="+value+"; Max-Age=604800; Secure; HttpOnly; SameSite=Lax",accepted -> {
                                        if(!Boolean.TRUE.equals(accepted))failure.set(new AssertionError("Cookie rejected"));
                                        m.flush(); web.destroy(); done.countDown();
                                    });
                                });
                            } else {
                                String cookies=m.getCookie(InstagramSession.ORIGIN);
                                if(cookies==null||!cookies.contains("sessionid="+value))failure.set(new AssertionError("Browser cookie did not survive"));
                                web.evaluateJavascript("localStorage.getItem('session-fixture')", stored -> {
                                    if(!"\"offline\"".equals(stored))failure.set(new AssertionError("Site storage did not survive"));
                                    web.destroy();done.countDown();
                                });
                            }
                        }
                    });
                    web.loadDataWithBaseURL(InstagramSession.ORIGIN,"<html><body>Offline session fixture</body></html>","text/html","UTF-8",null);
                } catch(Throwable e){failure.set(e);done.countDown();}
            });
            if(!done.await(20,TimeUnit.SECONDS))throw new AssertionError("Browser fixture timed out");
            if(failure.get()!=null)throw new AssertionError(failure.get());
            CountDownLatch manualDone=new CountDownLatch(1);
            host.runOnMainSync(() -> {
                try {
                    if(!InstagramSession.profiles())throw new AssertionError("MULTI_PROFILE unavailable; update Android System WebView");
                    CookieManager manual=InstagramSession.manager(true); manual.setAcceptCookie(true);
                    WebView isolated=new WebView(c); InstagramSession.attach(isolated,true);
                    Runnable verify=() -> {
                        try {
                            String own=manual.getCookie(InstagramSession.ORIGIN);
                            String persistent=CookieManager.getInstance().getCookie(InstagramSession.ORIGIN);
                            if(own==null||!own.contains("sessionid=MANUAL_"+value))throw new AssertionError("Manual profile cookie lost");
                            if(persistent==null||!persistent.contains("sessionid="+value)||persistent.contains("MANUAL_"))throw new AssertionError("Methods share a cookie store");
                            if(!InstagramSession.MANUAL_PROFILE.equals(androidx.webkit.WebViewCompat.getProfile(isolated).getName()))throw new AssertionError("Profile not attached");
                        }catch(Throwable error){failure.set(error);}
                        isolated.destroy(); manualDone.countDown();
                    };
                    if(seed)manual.setCookie(InstagramSession.ORIGIN,"sessionid=MANUAL_"+value+"; Max-Age=604800; Secure; HttpOnly",accepted -> {
                        if(!Boolean.TRUE.equals(accepted))failure.set(new AssertionError("Manual cookie rejected")); manual.flush(); verify.run();
                    });
                    else verify.run();
                }catch(Throwable error){failure.set(error);manualDone.countDown();}
            });
            if(!manualDone.await(20,TimeUnit.SECONDS)||failure.get()!=null)throw new AssertionError("Manual profile isolation failed");
            java.io.File file=new java.io.File(c.getFilesDir(),"session-thumbnail-sentinel.webp");
            if(seed) {
                Files.write(file.toPath(),"PRESERVED_THUMBNAIL".getBytes(StandardCharsets.UTF_8));
                SessionState.prefs(c).edit().putBoolean("webSessionHold",true).putString("sessionQueueSentinel","PENDING").commit();
            } else {
                if(!"PRESERVED_THUMBNAIL".equals(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)))throw new AssertionError("Thumbnail sentinel changed");
                if(!"PENDING".equals(SessionState.prefs(c).getString("sessionQueueSentinel","")))throw new AssertionError("Queue sentinel changed");
                if(!SessionState.prefs(c).getBoolean("webSessionHold",false))throw new AssertionError("Security hold lost");
            }
            if ("crash".equals(arguments.getString("scenario"))) {
                Bundle marker=new Bundle(); marker.putString("stream","CRASHING_OFFLINE_FIXTURE\n"); host.sendStatus(0,marker);
                android.os.Process.killProcess(android.os.Process.myPid()); return;
            }
            result.putString("stream","OFFLINE_SESSION_"+(seed?"SEEDED":"PRESERVED")+"\n");host.finish(Activity.RESULT_OK,result);
        } catch(Throwable error) { result.putString("stream","OFFLINE_SESSION_FAILED: "+error.getClass().getSimpleName()+"\n"); host.finish(Activity.RESULT_CANCELED,result); }
    }
}
