package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.webkit.CookieManager;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class ThreeSessionEnginesTest {
    Context c; File file;
    @Before public void setup(){c=RuntimeEnvironment.getApplication();SessionState.prefs(c).edit().clear().putInt("startMinute",0).putInt("endMinute",1439).putInt("minimum",1).putInt("maximum",1).commit();QueueControl.lock.set(false);CookieManager.getInstance().removeAllCookies(null);file=new File(c.getApplicationInfo().dataDir,"app_webview/Default/Cookies");file.getParentFile().mkdirs();SQLiteDatabase.deleteDatabase(file);}
    @After public void cleanup(){SQLiteDatabase.deleteDatabase(file);QueueControl.lock.set(false);}
    @Test public void removedHttpChoiceMigratesToLegacyWithoutErasingSession() {
        assertEquals("legacy",InstagramSession.engine(c));
        SessionState.prefs(c).edit().putString("browserEngine","native").commit();assertEquals("legacy",InstagramSession.engine(c));
        try{InstagramSession.selectEngine(c,"native");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void choicesPersistWithoutAlteringCookiesManualRecordQueueOrHold()throws Exception{
        CookieManager m=CookieManager.getInstance();m.setCookie(InstagramSession.ORIGIN,"sessionid=OFFLINE_KEEP; Secure; HttpOnly");
        SessionState.prefs(c).edit().putString("manualSessionStatus","Saved").putBoolean("webSessionHold",true).putString("tree","unchanged").commit();
        for(String engine:new String[]{"netscape","legacy"}) {
            InstagramSession.selectEngine(c,engine);
            assertEquals(engine,InstagramSession.engine(c.createPackageContext(c.getPackageName(),0)));
            assertFalse(InstagramSession.manual(c));assertTrue(m.getCookie(InstagramSession.ORIGIN).contains("OFFLINE_KEEP"));
            assertEquals("Saved",SessionState.prefs(c).getString("manualSessionStatus",""));
            assertEquals("unchanged",SessionState.prefs(c).getString("tree",""));assertFalse(SessionState.prefs(c).getBoolean("armed",true));
            try{InstagramSession.open(c);fail("Switch must not bypass security hold");}catch(ProfileProvider.Failure e){assertEquals("LOGIN_VERIFICATION_REQUIRED",e.code);}
        }
        InstagramSession.select(c,true);assertTrue(InstagramSession.manual(c));assertEquals("legacy",InstagramSession.engine(c));
    }
    @Test public void databaseNetscapePathIsDistinctAndKeepsExpiryScopeAndEncodedValues()throws Exception {
        long future=System.currentTimeMillis()/1000+3600;
        CookieManager.getInstance().setCookie(InstagramSession.ORIGIN,"sessionid=OFFLINE_DB%3AVALUE; Secure; HttpOnly");
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null)) {
            db.execSQL("CREATE TABLE cookies(host_key TEXT,path TEXT,name TEXT,value TEXT,is_secure INTEGER,expires_utc INTEGER)");
            db.execSQL("INSERT INTO cookies VALUES(?,?,?,?,?,?)",new Object[]{".instagram.com","/","sessionid","OFFLINE_DB%3AVALUE",1,(future+11644473600L)*1000000L});
            db.execSQL("INSERT INTO cookies VALUES(?,?,?,?,?,?)",new Object[]{".evilinstagram.com","/","foreign","NO",1,0});
            db.execSQL("INSERT INTO cookies VALUES(?,?,?,?,?,?)",new Object[]{".instagram.com","/","expired","NO",1,11644473601000000L});
        }
        CookieJar jar=NetscapeBrowserCookies.read(c);assertEquals("OFFLINE_DB%3AVALUE",jar.value("sessionid",System.currentTimeMillis()));
        assertEquals(1,jar.entries.size());assertEquals(future,jar.entries.get(0).expires);assertTrue(jar.entries.get(0).subdomains);
        assertEquals("OFFLINE_DB%3AVALUE",BrowserCookies.read(c).value("sessionid",System.currentTimeMillis()));
    }
    @Test public void legacyProductionFactoryReusesDefaultBrowserWithoutInjectionOrTest()throws Exception {
        CookieManager m=CookieManager.getInstance();m.setCookie(InstagramSession.ORIGIN,"sessionid=OFFLINE_KEEP; Secure; HttpOnly");
        InstagramSession.selectEngine(c,"legacy");
        try(ProfileProvider p=InstagramSession.open(c)) {
            assertTrue(p instanceof WebViewProfileProvider);assertFalse(p.requiresAuthentication());
            assertNull(org.robolectric.util.ReflectionHelpers.getField(p,"cookies"));
            assertEquals("OFFLINE_KEEP",CookieJar.parse(m.getCookie(InstagramSession.ORIGIN),System.currentTimeMillis()).value("sessionid",0));
        }
    }
    @Test public void actualSecurityResponseSurvivesEngineSwitchWithoutPreflight()throws Exception {
        CookieJar jar=CookieJar.parse("sessionid=OFFLINE_STOP",System.currentTimeMillis());
        try(BrowserProfileProvider p=new BrowserProfileProvider(c,jar,url->new ProviderTest.Stub(url,401,"expired"),()->{throw new AssertionError("No security fallback");})) {
            try{p.fetch("example",true);fail();}catch(ProfileProvider.Failure e){assertEquals("COOKIE_INVALID_EXPIRED",e.code);}
        }
        InstagramSession.selectEngine(c,"legacy");
        try{InstagramSession.open(c);fail("A method switch must not bypass an actual security response");}catch(ProfileProvider.Failure e){assertEquals("LOGIN_VERIFICATION_REQUIRED",e.code);}
    }
    @Test public void browserProviderBulkSkipsAuthenticationAndFetchesRealTask()throws Exception {
        ProfileProvider p=new ProfileProvider(){public boolean requiresAuthentication(){return false;}public void authenticate(){throw new AssertionError("No mandatory cookie test");}public byte[] fetch(String user){return new byte[]{7};}public void cancel(){}};
        FetchBroker broker=new FetchBroker(c,full->p);SessionState.prefs(c).edit().putBoolean("bulkDisabled",false).commit();
        try{broker.beginBatch();assertArrayEquals(new byte[]{7},broker.submit("example",false).get(5,TimeUnit.SECONDS));}finally{broker.endBatch();broker.cancelAll();}
    }
}
