package com.instagram.insfav.fetcher;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.webkit.CookieManager;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;

/** Real CookieManager, Keystore, production factory, HTTP headers/parser, HD and Binder SAF write. */
final class NativeCookieHdDeviceCheck {
    static void run(Instrumentation host) throws Exception { run(host, "native"); }
    static void run(Instrumentation host, String engine) throws Exception {
        Context c=host.getTargetContext();String user="native_cookie_hd";
        c.sendBroadcast(new Intent().setClassName(c.getPackageName()+".test",FixtureDocuments.Grant.class.getName()));
        long until=SystemClock.elapsedRealtime()+10000;boolean granted=false;
        while(!granted&&SystemClock.elapsedRealtime()<until)try{c.getContentResolver().takePersistableUriPermission(FixtureDocuments.tree(),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);granted=true;}catch(SecurityException e){Thread.sleep(100);}
        if(!granted)throw new AssertionError("Offline SAF grant unavailable");
        CountDownLatch cookies=new CountDownLatch(1);
        host.runOnMainSync(()->{
            CookieManager m=CookieManager.getInstance();m.setAcceptCookie(true);
            m.setCookie(InstagramSession.ORIGIN,"ds_user_id=123; Secure",ignored->m.setCookie(InstagramSession.ORIGIN,"sessionid=OFFLINE_NATIVE_HD%3AFIXTURE; Secure; HttpOnly",accepted->{m.flush();cookies.countDown();}));
        });
        if(!cookies.await(10,TimeUnit.SECONDS))throw new AssertionError("Offline browser cookie setup timed out");
        CountDownLatch written=new CountDownLatch(1);
        Fetcher.initialize(c,new Fetcher.Host(){public Collection<String> users(){return Arrays.asList(user);}public String tree(){return FixtureDocuments.tree().toString();}public String directory(){return "root";}public long revision(){return 0;}public boolean hasImage(String name){return false;}public void imageWritten(String name,Uri uri,String tree){written.countDown();}});
        Fetcher.setBulkDisabled(true);
        SessionState.prefs(c).edit().putBoolean("manualSession",false).putBoolean("instagramSecurityHold",false).putBoolean("webSessionHold",false).putString("blocked","").putLong("retryAt",0).commit();
        try{DocumentsContract.deleteDocument(c.getContentResolver(),DocumentsContract.buildDocumentUriUsingTree(FixtureDocuments.tree(),"root/"+user+".webp"));}catch(Exception ignored){}
        Bitmap image=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888);image.eraseColor(Color.BLUE);ByteArrayOutputStream png=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,png);image.recycle();
        InstagramSession.selectEngine(c,engine);
        ProfileProvider provider=InstagramSession.open(c);
        AtomicInteger calls=new AtomicInteger();
        PublicProfileProvider.Connections transport=url->{calls.incrementAndGet();boolean profile=url.getHost().equals("www.instagram.com");
            byte[] body=profile?("{\"status\":\"ok\",\"data\":{\"user\":{\"username\":\""+user+"\",\"id\":456,\"profile_pic_url\":\"https://fixture.cdninstagram.com/small.png\",\"hd_profile_pic_url_info\":{\"url\":\"https://fixture.cdninstagram.com/hd.png\"}}}}").getBytes(StandardCharsets.UTF_8):png.toByteArray();
            return new HttpsURLConnection(url){
                public int getResponseCode(){if(profile){if(!url.getPath().endsWith("web_profile_info/")||!getRequestProperty("Cookie").contains("OFFLINE_NATIVE_HD%3AFIXTURE"))throw new AssertionError("Extracted cookies did not reach the profile request");}else if(getRequestProperty("Cookie")!=null)throw new AssertionError("Session cookies leaked to CDN");return 200;}
                public InputStream getInputStream(){return new ByteArrayInputStream(body);}public String getContentType(){return profile?"application/json":"image/png";}public long getContentLengthLong(){return body.length;}
                public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}public String getCipherSuite(){return "TLS";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
            };
        };
        if(provider instanceof BrowserProfileProvider) {
            java.lang.reflect.Field network=BrowserProfileProvider.class.getDeclaredField("network");network.setAccessible(true);network.set(provider,transport);
        } else {
            java.lang.reflect.Field resources=WebViewProfileProvider.class.getDeclaredField("resources");resources.setAccessible(true);
            resources.set(provider,(WebViewProfileProvider.Resources)url->{
                if(url.endsWith("/favicon.ico"))return new android.webkit.WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                calls.incrementAndGet();
                if(!url.equals(InstagramSession.ORIGIN+user+"/"))throw new AssertionError("Legacy HD must open profile directly without authentication preflight");
                String header=CookieManager.getInstance().getCookie(InstagramSession.ORIGIN);
                if(header==null||!header.contains("OFFLINE_NATIVE_HD%3AFIXTURE"))throw new AssertionError("Legacy browser did not retain its session");
                String html="<html><body><script type='application/json'>{\"viewerId\":\"123\",\"user\":{\"username\":\""+user+"\",\"id\":\"456\",\"has_anonymous_profile_picture\":false,\"hd_profile_pic_url_info\":{\"url\":\"https://fixture.cdninstagram.com/hd.png\"}}}</script></body></html>";
                return new android.webkit.WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
            });
            java.lang.reflect.Field downloads=WebViewProfileProvider.class.getDeclaredField("downloads");downloads.setAccessible(true);
            downloads.set(provider,(WebViewProfileProvider.Downloads)()->new PublicProfileProvider(transport,null,new File(c.getCacheDir(),"profile-downloads")));
        }
        FetchBroker broker=new FetchBroker(c,full->provider);java.lang.reflect.Field instance=FetchBroker.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,broker);
        try{
            Fetcher.view(user,"nativeCookieHD");
            if(!written.await(25,TimeUnit.SECONDS))throw new AssertionError("Native cookie -> HD -> 256 WebP write did not complete");
            if(calls.get()!=2||!new OutputFolder(c,FixtureDocuments.tree().toString()).valid(user+".webp"))throw new AssertionError("Wrong HD transport or invalid 256 WebP");
            if(!Fetcher.bulkDisabled()||SessionState.prefs(c).getBoolean("armed",true))throw new AssertionError("HD started the bulk queue");
            File snapshot=new File(c.getFilesDir(),engine.equals("netscape") ? "instagram-netscape-cookies.enc" : "instagram-webview-cookies.enc");
            if(!engine.equals("legacy") && (!snapshot.isFile()||new String(java.nio.file.Files.readAllBytes(snapshot.toPath()),StandardCharsets.ISO_8859_1).contains("OFFLINE_NATIVE_HD")))throw new AssertionError("Browser snapshot was not encrypted");
        }finally{Fetcher.closeViewer();broker.cancelAll();provider.close();Fetcher.setBulkDisabled(false);}
    }
}
