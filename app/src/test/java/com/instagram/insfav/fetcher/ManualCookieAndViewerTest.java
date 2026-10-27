package com.instagram.insfav.fetcher;

import android.content.*;
import java.net.URL;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28, manifest=Config.NONE)
public class ManualCookieAndViewerTest {
    Context c; SharedPreferences p;
    @Before public void setup() {
        c=RuntimeEnvironment.getApplication(); p=SessionState.prefs(c);
        p.edit().clear().putInt("minimum",1).putInt("maximum",1).putInt("startMinute",0).putInt("endMinute",1439)
            .putBoolean("userPaused",true).putBoolean("armed",false).commit();
    }
    @Test public void mixedBrowserExportPreservesEncodedInstagramCookiesOnly() throws Exception {
        String data="\uFEFF# Netscape HTTP Cookie File\r\n.mozilla.org\tTRUE\t/\tFALSE\t2100000000\t_ga\tFIXTURE_ANALYTICS\r\n"
            +".google.com\tTRUE\t/\tTRUE\t2100000000\tNID\tFIXTURE_FOREIGN\r\n"
            +".instagram.com\tTRUE\t/\tTRUE\t2100000000\tcsrftoken\tFIXTURE_CSRF\r\n"
            +"#HttpOnly_.instagram.com\tTRUE\t/\tTRUE\t2100000000\tsessionid\t123%3AFIXTURE%3A23\r\n"
            +".instagram.com\tTRUE\t/\tTRUE\t0\trur\tREGION%2C123%2CFIXTURE\r\n";
        for(String input:new String[]{data,data.substring(1).replace('\t',' ')}) {
            CookieJar jar=CookieJar.parse(input,1000);
            assertEquals("123%3AFIXTURE%3A23",jar.value("sessionid",1000));
            String header=jar.header(new URL("https://www.instagram.com/"),1000);
            assertTrue(header.contains("rur=REGION%2C123%2CFIXTURE"));
            assertFalse(header.contains("FOREIGN"));assertFalse(header.contains("ANALYTICS"));
        }
    }
    @Test public void successfulVerificationClearsOnlyTheResolvedSessionStop() {
        InstagramSession.blocked(c,false,"COOKIE_INVALID_EXPIRED");
        InstagramSession.recordVerified(c,false);
        assertEquals("",p.getString("blocked",""));
        assertFalse(p.getBoolean("armed",true));assertTrue(p.getBoolean("userPaused",false));
        p.edit().putString("blocked","RATE_LIMITED").putLong("retryAt",999999L).commit();
        InstagramSession.recordVerified(c,false);
        assertEquals("RATE_LIMITED",p.getString("blocked",""));assertEquals(999999L,p.getLong("retryAt",0));
    }
    @Test public void viewerIgnoresBulkPacingAndNonSecurityStopWithoutArmingBulk() throws Exception {
        long now=System.currentTimeMillis(), later=now+3600000;
        p.edit().putLong("nextRequestAt",later).putLong("workerNextAt_0",later).putString("blocked","WEBVIEW_TIMEOUT").commit();
        AtomicInteger calls=new AtomicInteger();
        FetchBroker.Time clock=new FetchBroker.Time(){public long now(){return now;} public void sleep(long millis)throws InterruptedException{Thread.sleep(10);}};
        FetchBroker broker=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String user){calls.incrementAndGet();return new byte[]{1};}public void cancel(){}},clock);
        try {
            assertArrayEquals(new byte[]{1},broker.submit("example",true).get(3,TimeUnit.SECONDS));
            assertEquals(1,calls.get());assertEquals(later,p.getLong("workerNextAt_0",0));
            assertFalse(broker.batch);assertFalse(p.getBoolean("armed",true));assertTrue(p.getBoolean("userPaused",false));
            assertFalse(WorkScheduler.enabled(p));assertEquals("WEBVIEW_TIMEOUT",p.getString("blocked",""));
        } finally {broker.cancelAll();}
    }
    @Test public void expiredServerCooldownAllowsExplicitHdWithoutResumingBulk() throws Exception {
        p.edit().putString("blocked","RATE_LIMITED").putLong("retryAt",System.currentTimeMillis()-1000).commit();
        FetchBroker broker=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String user){return new byte[]{1};}public void cancel(){}},new TestTime());
        try {assertArrayEquals(new byte[]{1},broker.submit("example",true).get(3,TimeUnit.SECONDS));
            assertEquals("RATE_LIMITED",p.getString("blocked",""));assertFalse(p.getBoolean("armed",true));assertFalse(broker.batch);
        } finally {broker.cancelAll();}
    }
    @Test public void viewerStillHonorsSecurityAndRateStops() throws Exception {
        for(String code:new String[]{"CHALLENGE_REQUIRED","COOKIE_INVALID_EXPIRED","RATE_LIMITED"}) {
            p.edit().putString("blocked",code).putLong("retryAt",System.currentTimeMillis()+60000).commit();
            AtomicInteger opens=new AtomicInteger();
            FetchBroker broker=new FetchBroker(c,full->{opens.incrementAndGet();throw new ProfileProvider.Failure("UNEXPECTED");},new TestTime());
            try {broker.submit("example",true).get(3,TimeUnit.SECONDS);fail();}
            catch(ExecutionException expected){assertEquals(code,((ProfileProvider.Failure)expected.getCause()).code);}
            finally {broker.cancelAll();}
            assertEquals(0,opens.get());
        }
    }
}
