package com.instagram.insfav.fetcher;
import android.content.*;
import android.net.Uri;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28, manifest=Config.NONE)
public class InstagramSessionTest {
    Context c;
    @Before public void setup() { c=RuntimeEnvironment.getApplication(); SessionState.prefs(c).edit().clear().commit(); QueueControl.lock.set(false); }
    @Test public void defaultIsWebViewAndMethodChangesKeepBothRecords() throws Exception {
        assertFalse(InstagramSession.manual(c));
        CookieVault vault=new CookieVault(c,()->new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"));
        vault.save(CookieJar.parse("sessionid=OFFLINE_ONLY; ds_user_id=123",System.currentTimeMillis()));
        String original=vault.stored();
        SessionState.prefs(c).edit().putString("webSessionStatus","Verified").putString("manualSessionStatus","Not verified").putString("tree","kept").commit();
        InstagramSession.select(c,true); assertTrue(InstagramSession.manual(c));
        InstagramSession.select(c,false); assertEquals("Verified",SessionState.prefs(c).getString("webSessionStatus",""));
        assertEquals(original,vault.stored()); assertEquals("kept",SessionState.prefs(c).getString("tree","")); vault.remove();
    }
    @Test public void securityHoldSurvivesNewContextAndMethodSwitch() throws Exception {
        InstagramSession.blocked(c,false,"CHALLENGE_REQUIRED");
        Context reopened=c.createPackageContext(c.getPackageName(),0);
        assertTrue(SessionState.prefs(reopened).getBoolean("webSessionHold",false));
        try { InstagramSession.requireReady(reopened); fail(); } catch(ProfileProvider.Failure e) { assertEquals("LOGIN_VERIFICATION_REQUIRED",e.code); }
        InstagramSession.select(c,true); InstagramSession.requireReady(c);
        InstagramSession.select(c,false);
        try { InstagramSession.requireReady(c); fail(); } catch(ProfileProvider.Failure expected) { }
    }
    @Test public void verifiedSessionDoesNotResumeQueueOrChangeManualEvidence() {
        InstagramSession.blocked(c,false,"COOKIE_INVALID_EXPIRED");
        SessionState.prefs(c).edit().putBoolean("manualSessionHold",true).commit();
        InstagramSession.recordVerified(c,false);
        assertFalse(SessionState.prefs(c).getBoolean("webSessionHold",true));
        assertTrue(SessionState.prefs(c).getBoolean("manualSessionHold",false));
        assertFalse(SessionState.prefs(c).getBoolean("armed",true));
        assertTrue(SessionState.prefs(c).getBoolean("userPaused",false));
    }
    @Test public void loginNavigationKeepsCredentialsOnInstagramHttpsOnly() {
        assertTrue(InstagramLoginActivity.instagram(Uri.parse("https://www.instagram.com/challenge/")));
        for(String url:new String[]{"http://www.instagram.com/","https://www.instagram.com.evil.test/","https://evil.test/","file:///tmp/login","https://www.instagram.com:444/","https://user@instagram.com/"})
            assertFalse(url,InstagramLoginActivity.instagram(Uri.parse(url)));
    }
}
