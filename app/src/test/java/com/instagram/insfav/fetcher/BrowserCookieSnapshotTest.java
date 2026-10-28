package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.webkit.CookieManager;
import java.io.File;
import java.nio.file.Files;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class BrowserCookieSnapshotTest {
    Context c; File dbfile;
    final CookieVault.Keys keys=()->new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");
    @Before public void before() {
        c=RuntimeEnvironment.getApplication();SessionState.prefs(c).edit().clear().commit();QueueControl.lock.set(false);
        CookieManager.getInstance().removeAllCookies(null);cookie("OFFLINE_CURRENT%3AFIXTURE");
        dbfile=new File(c.getApplicationInfo().dataDir,"app_webview/Default/Cookies");dbfile.getParentFile().mkdirs();SQLiteDatabase.deleteDatabase(dbfile);
    }
    @After public void after(){SQLiteDatabase.deleteDatabase(dbfile);}
    void cookie(String value){CookieManager.getInstance().setCookie(InstagramSession.ORIGIN,"sessionid="+value+"; Secure; HttpOnly");}
    void database(String value)throws Exception{
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(dbfile,null)){
            db.execSQL("CREATE TABLE cookies(host_key TEXT,path TEXT,name TEXT,value TEXT,expires_utc INTEGER)");
            if(value!=null)db.execSQL("INSERT INTO cookies VALUES(?,?,?,?,?)",new Object[]{".instagram.com","/","sessionid",value,(System.currentTimeMillis()/1000+3600+11644473600L)*1000000L});
        }
    }
    CookieVault vault(String engine){return new CookieVault(c,keys,engine.equals("netscape")?"instagram-netscape-cookies.enc":"instagram-webview-cookies.enc");}
    @Test public void missingEmptyAndUnsupportedDatabaseStillExtractLiveHttpOnly()throws Exception{
        assertEquals("OFFLINE_CURRENT%3AFIXTURE",NetscapeBrowserCookies.read(c).value("sessionid",System.currentTimeMillis()));
        database(null);assertEquals("OFFLINE_CURRENT%3AFIXTURE",NetscapeBrowserCookies.read(c).value("sessionid",System.currentTimeMillis()));
        SQLiteDatabase.deleteDatabase(dbfile);
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(dbfile,null)){db.execSQL("CREATE TABLE incompatible(value TEXT)");}
        assertEquals("OFFLINE_CURRENT%3AFIXTURE",NetscapeBrowserCookies.read(c).value("sessionid",System.currentTimeMillis()));
    }
    @Test public void staleOrDeletedDatabaseValuesCannotReviveOrReplaceLiveCookie()throws Exception{
        database("OFFLINE_OLD");CookieJar jar=NetscapeBrowserCookies.read(c);
        assertEquals("OFFLINE_CURRENT%3AFIXTURE",jar.value("sessionid",System.currentTimeMillis()));assertEquals(0,jar.entries.get(0).expires);
        CookieManager.getInstance().removeAllCookies(null);
        try{NetscapeBrowserCookies.read(c);fail();}catch(ProfileProvider.Failure e){assertEquals("COOKIE_INVALID",e.code);}
    }
    @Test public void matchingChromiumMetadataKeepsScopeAndRealUnixExpiry()throws Exception{
        database("OFFLINE_CURRENT%3AFIXTURE");CookieJar jar=NetscapeBrowserCookies.read(c);
        assertTrue(jar.entries.get(0).subdomains);assertEquals("instagram.com",jar.entries.get(0).domain);
        assertTrue(jar.entries.get(0).expires>System.currentTimeMillis()/1000);
        assertTrue(jar.entries.get(0).expires<System.currentTimeMillis()/1000+4000);
    }
    @Test public void netscapeRoundTripPreservesEncodedValuesAndIgnoresOtherDomains()throws Exception{
        String text="# Netscape HTTP Cookie File\n.mozilla.org\tTRUE\t/\tTRUE\t0\tother\tOFFLINE\n#HttpOnly_.instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tOFFLINE_CURRENT%3AFIXTURE\n.instagram.com\tTRUE\t/\tTRUE\t0\trur\tODN%2C123%3ATEST\n";
        CookieJar jar=CookieJar.parse(text,System.currentTimeMillis());String serialized=NetscapeBrowserCookies.text(jar);
        assertEquals(jar.encode(),CookieJar.parse(serialized,System.currentTimeMillis()).encode());assertFalse(serialized.contains("mozilla"));
    }
    @Test public void changedCookiesAreEncryptedAndUnchangedCookiesAreNotRewritten()throws Exception{
        CookieJar first=NetscapeBrowserCookies.read(c);CookieVault v=vault("netscape");String epoch=BrowserCookieStore.revision(c,"netscape");
        assertTrue(BrowserCookieStore.save(c,"netscape",first,epoch,true,v));
        File file=new File(c.getFilesDir(),"instagram-netscape-cookies.enc");byte[] original=Files.readAllBytes(file.toPath());
        assertFalse(new String(original,java.nio.charset.StandardCharsets.ISO_8859_1).contains("OFFLINE_CURRENT"));
        assertFalse(BrowserCookieStore.save(c,"netscape",first,epoch,false,v));assertArrayEquals(original,Files.readAllBytes(file.toPath()));
        cookie("OFFLINE_ROTATED");CookieJar next=NetscapeBrowserCookies.read(c);
        assertTrue(BrowserCookieStore.save(c,"netscape",next,epoch,false,v));assertEquals("OFFLINE_ROTATED",v.load().value("sessionid",System.currentTimeMillis()));
        assertFalse(SessionState.prefs(c).getBoolean("armed",false));assertNotEquals("Verified",SessionState.prefs(c).getString("webSessionStatus",""));
    }
    @Test public void removalIsPerMethodPreservesLiveLoginAndRejectsStaleOrAutomaticResurrection()throws Exception{
        CookieJar jar=NetscapeBrowserCookies.read(c);CookieVault v=vault("netscape"),other=vault("legacy");
        v.save(jar);other.save(jar);CookieVault manual=new CookieVault(c,keys);manual.save(jar);
        String old=BrowserCookieStore.revision(c,"netscape");BrowserCookieStore.remove(c,"netscape");
        assertFalse(v.configured());assertTrue(other.configured());assertTrue(manual.configured());
        assertEquals("OFFLINE_CURRENT%3AFIXTURE",NetscapeBrowserCookies.read(c).value("sessionid",System.currentTimeMillis()));
        try{BrowserCookieStore.save(c,"netscape",jar,old,true,v);fail();}catch(ProfileProvider.Failure e){assertEquals("REQUEST_CANCELLED",e.code);}
        assertFalse(BrowserCookieStore.save(c,"netscape",jar,BrowserCookieStore.revision(c,"netscape"),false,v));assertFalse(v.configured());
        assertTrue(BrowserCookieStore.save(c,"netscape",jar,BrowserCookieStore.revision(c,"netscape"),true,v));assertFalse(BrowserCookieStore.removed(c,"netscape"));
    }
    @Test public void logoutEpochRejectsCaptureAndBothEnginesUseWorkingBrowserWithoutPreflight()throws Exception{
        CookieJar jar=NetscapeBrowserCookies.read(c);String epoch=BrowserCookieStore.revision(c,"netscape");
        SessionState.prefs(c).edit().putLong("browserCookieEpoch",1).commit();
        try{BrowserCookieStore.save(c,"netscape",jar,epoch,true,vault("netscape"));fail();}catch(ProfileProvider.Failure e){assertEquals("REQUEST_CANCELLED",e.code);}
        for(String engine:new String[]{"legacy","netscape"}){
            InstagramSession.selectEngine(c,engine);
            try(ProfileProvider p=InstagramSession.open(c)){assertTrue(p instanceof WebViewProfileProvider);assertFalse(p.requiresAuthentication());assertNull(org.robolectric.util.ReflectionHelpers.getField(p,"cookies"));}
        }
    }
}
