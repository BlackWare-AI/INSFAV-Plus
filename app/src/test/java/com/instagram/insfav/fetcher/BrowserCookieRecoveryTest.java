package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;
import android.content.Context;
import android.webkit.CookieManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class BrowserCookieRecoveryTest {
    static final String HD="https://fixture.cdninstagram.com/hd.png";
    Context c;
    @Before public void setup(){c=RuntimeEnvironment.getApplication();CookieManager.getInstance().removeAllCookies(null);SessionState.prefs(c).edit().clear().commit();}
    static String profile(){return "{\"data\":{\"user\":{\"username\":\"example\",\"id\":\"456\",\"profile_pic_url\":\"https://fixture.cdninstagram.com/small.png\",\"hd_profile_pic_url_info\":{\"url\":\""+HD+"\"}}},\"status\":\"ok\"}";}
    CookieJar browser()throws Exception{
        CookieManager.getInstance().setCookie(InstagramSession.ORIGIN,"sessionid=OFFLINE_COOKIE%3AFIXTURE; Secure; HttpOnly");
        CookieManager.getInstance().setCookie(InstagramSession.ORIGIN,"ds_user_id=123; Secure");
        return BrowserCookies.read(c);
    }
    @Test public void capturedHttpOnlyCookiesReachProfileApiAndHdNeverReachesHomeOrLeaksCookiesToCdn()throws Exception{
        CookieJar jar=browser();assertEquals("OFFLINE_COOKIE%3AFIXTURE",jar.value("sessionid",System.currentTimeMillis()));
        AtomicInteger calls=new AtomicInteger();
        BrowserProfileProvider provider=new BrowserProfileProvider(c,jar,url->{calls.incrementAndGet();
            if(url.getHost().equals("www.instagram.com"))return new ProviderTest.Stub(url,200,profile()){
                @Override public int getResponseCode(){assertTrue(url.getPath().endsWith("web_profile_info/"));assertTrue(getRequestProperty("Cookie").contains("sessionid=OFFLINE_COOKIE%3AFIXTURE"));assertEquals("936619743392459",getRequestProperty("X-IG-App-ID"));return 200;}};
            assertEquals(HD,url.toString());return new ProviderTest.Stub(url,200,"IMAGE_FIXTURE"){
                @Override public String getContentType(){return "image/png";}
                @Override public int getResponseCode(){assertNull(getRequestProperty("Cookie"));return 200;}};
        },()->{throw new AssertionError("No hidden WebView or home-page preflight is needed");});
        try{assertEquals("IMAGE_FIXTURE",new String(provider.fetch("example",true),StandardCharsets.UTF_8));assertEquals(2,calls.get());assertNotEquals("Verified",SessionState.prefs(c).getString("webSessionStatus",""));}finally{provider.close();}
    }
    @Test public void unavailableJsonEndpointStillUsesAuthenticatedProfileHtmlBeforeAnyWebView()throws Exception {
        AtomicInteger requests=new AtomicInteger();
        BrowserProfileProvider p=new BrowserProfileProvider(c,browser(),url->{requests.incrementAndGet();
            if(url.getPath().startsWith("/api/"))return new ProviderTest.Stub(url,404,"unavailable");
            if(url.getHost().equals("www.instagram.com"))return new ProviderTest.Stub(url,200,ProviderTest.page("example","false"));
            return new ProviderTest.Stub(url,200,"PIXELS"){@Override public String getContentType(){return "image/jpeg";}};
        },()->{throw new AssertionError("HTML transport can resolve the same account");});
        try{assertEquals("PIXELS",new String(p.fetch("example",true),StandardCharsets.UTF_8));assertEquals(3,requests.get());}finally{p.close();}
    }
    @Test public void ownChromiumMetadataKeepsExpiryAndNeverReadsForeignDomain()throws Exception {
        CookieJar current=browser();java.io.File file=new java.io.File(c.getApplicationInfo().dataDir,"app_webview/Default/Cookies");file.getParentFile().mkdirs();
        long future=System.currentTimeMillis()/1000+3600;
        try(android.database.sqlite.SQLiteDatabase sql=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null)) {
            sql.execSQL("CREATE TABLE cookies (host_key TEXT,path TEXT,name TEXT,value TEXT,expires_utc INTEGER)");
            sql.execSQL("INSERT INTO cookies VALUES (?,?,?,?,?)",new Object[]{".instagram.com","/","sessionid","OLD_DB_VALUE",(future+11644473600L)*1000000L});
            sql.execSQL("INSERT INTO cookies VALUES (?,?,?,?,?)",new Object[]{".evilinstagram.com","/","foreign","NOT_OURS",0});
        }
        try{CookieJar read=BrowserCookies.read(c);assertEquals(current.value("sessionid",0),read.value("sessionid",System.currentTimeMillis()));assertEquals(future,read.entries.stream().filter(e->e.name.equals("sessionid")).findFirst().get().expires);assertFalse(read.entries.stream().anyMatch(e->e.name.equals("foreign")));}
        finally{android.database.sqlite.SQLiteDatabase.deleteDatabase(file);}
    }
    @Test public void explicitSecurityResponsesNeverTriggerFallback()throws Exception{
        for(int status:new int[]{401,403,429}){
            BrowserProfileProvider provider=new BrowserProfileProvider(c,browser(),url->new ProviderTest.Stub(url,status,"blocked"),()->{throw new AssertionError("Security stop must not be bypassed");});
            try{provider.fetch("example",true);fail();}catch(ProfileProvider.Failure e){assertTrue(FailurePolicy.pause(e));assertEquals(status,e.http);}finally{provider.close();}
        }
    }
    @Test public void status200SecurityAndRateMessagesStopWithoutProfileOrWebViewFallback()throws Exception {
        for(String message:new String[]{"feedback_required","checkpoint_required","Please wait a few minutes before you try again."}) {
            AtomicInteger calls=new AtomicInteger();BrowserProfileProvider p=new BrowserProfileProvider(c,browser(),url->{calls.incrementAndGet();return new ProviderTest.Stub(url,200,"{\"status\":\"fail\",\"message\":\""+message+"\"}");},()->{throw new AssertionError("Explicit security message must stop all fallbacks");});
            try{p.fetch("example",true);fail();}catch(ProfileProvider.Failure e){assertTrue(FailurePolicy.pause(e));assertEquals(1,calls.get());}finally{p.close();}
        }
    }
    @Test public void sameCookieJarCanAuthenticateActualCurrentUserResponse()throws Exception{
        BrowserProfileProvider p=new BrowserProfileProvider(c,browser(),url->new ProviderTest.Stub(url,200,"{\"status\":\"ok\",\"user\":{\"pk\":123,\"username\":\"owner\"}}"),()->{throw new AssertionError();});
        try{p.authenticate();assertEquals("Verified",SessionState.prefs(c).getString("webSessionStatus",""));assertFalse(SessionState.prefs(c).getBoolean("armed",false));}finally{p.close();}
    }
}
