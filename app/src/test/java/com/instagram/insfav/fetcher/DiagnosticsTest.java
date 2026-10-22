package com.instagram.insfav.fetcher;

import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DiagnosticsTest {
    @Test public void exportContainsCodesButNoArbitraryMessages() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Diagnostics log = new Diagnostics(context);
        log.log("PROFILE", "RATE_LIMITED", 429); log.log("PROFILE", "https://example.test/session?token=SECRET", 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); log.export(output); String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("RATE_LIMITED 429")); assertFalse(text.contains("SECRET")); assertFalse(text.contains("https://example.test"));
        assertEquals("IO_ERROR", Diagnostics.storageCode(new IOException("PRIVATE_ACCOUNT")));
    }
    @Test public void structuredOutcomesAndBatchSessionEventsSurviveExportWithoutProbePayload() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        SessionState.prefs(c).edit().putLong("diagnosticBatch", 7).putInt("concurrentWebViews", 10).commit();
        Diagnostics d = new Diagnostics(c);
        d.session("SHARED_COOKIES_APPLIED"); d.session("SESSION_UNKNOWN");
        d.outcome("WEBVIEW_TIMEOUT", Diagnostics.reason("DEADLINE"), 0, false, true, "UNKNOWN", 15000, 15000);
        d.outcome("PROFILE_PAGE_UNAVAILABLE", Diagnostics.reason("https://private.test/SECRET"), 200, true, false, "SECRET", 42, 15000);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); d.export(out); String text = out.toString(StandardCharsets.UTF_8);
        for (String field : new String[]{"code=WEBVIEW_TIMEOUT", "reason=DEADLINE", "http=0", "pageFinished=false", "candidateFound=true", "session=UNKNOWN", "elapsedMs=15000", "timeoutMs=15000", "workers=10", "effectiveWorkers=", "activeAccounts=", "queuedAccounts=", "queue=", "batch=7", "SESSION SHARED_COOKIES_APPLIED 7", "SESSION SESSION_UNKNOWN 7", "reason=UNKNOWN_RESPONSE"}) assertTrue(field, text.contains(field));
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("private.test"));
        assertFalse(text.contains("SESSION SESSION_VERIFIED"));
        System.out.println("OFFLINE_FIXTURE_DIAGNOSTICS_BEGIN\n" + text + "OFFLINE_FIXTURE_DIAGNOSTICS_END");
    }
    @Test public void privateOutcomeLedgerExportsOnlyCountsAndCodes()throws Exception {
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("fetcher-queue.db");
        try(QueueStore q=new QueueStore(c)){q.sync(java.util.Arrays.asList("private.fixture","private.timeout"));q.mark("private.fixture","SKIPPED_UNAVAILABLE","PROFILE_PAGE_UNAVAILABLE");q.attempted("private.timeout");q.mark("private.timeout","RETRY_LATER","WEBVIEW_TIMEOUT");}
        ByteArrayOutputStream out=new ByteArrayOutputStream();new Diagnostics(c).export(out);String text=out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Saved profile outcomes (private ledger): 2"));assertTrue(text.contains("Deferred profiles awaiting explicit Retry: 1"));assertFalse(text.contains("private.fixture"));assertFalse(text.contains("private.timeout"));
    }
 @Test public void unexpectedErrorContainsOnlyFixedCategoryAndNumericLocation()throws Exception{
  Diagnostics d=new Diagnostics(RuntimeEnvironment.getApplication());d.unexpected("PROFILE",new IllegalArgumentException("sessionid=SECRET https://private.test/person PRIVATE_USER"));
  java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();d.export(out);String text=out.toString("UTF-8");assertTrue(text.contains("UNEXPECTED_ARGUMENT"));assertFalse(text.contains("SECRET"));assertFalse(text.contains("private.test"));assertFalse(text.contains("PRIVATE_USER"));
 }
}
