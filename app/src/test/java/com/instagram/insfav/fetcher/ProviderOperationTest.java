package com.instagram.insfav.fetcher;
import android.content.Context;import android.os.*;import android.webkit.*;import org.json.*;import org.junit.*;import org.junit.runner.RunWith;
import org.robolectric.*;import org.robolectric.annotation.*;import org.robolectric.shadows.ShadowSystemClock;import java.time.Duration;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE) @LooperMode(LooperMode.Mode.PAUSED)
public class ProviderOperationTest {
 WebViewProfileProvider provider;Context c;
 static class Page extends WebView {
  boolean brokenReset, memory;ValueCallback<String> pending;String url="https://www.instagram.com/example/";
  Page(Context c){super(c);}
  @Override public String getUrl(){return url;}
  @Override public void evaluateJavascript(String script,ValueCallback<String> result){if(memory)throw new OutOfMemoryError("fixture");pending=result;}
  @Override public void loadUrl(String value){if(value.equals("about:blank")&&brokenReset)throw new IllegalStateException("fixture");url=value;if(value.equals("about:blank"))getWebViewClient().onPageFinished(this,value);}
  void emit(JSONObject data){ValueCallback<String> callback=pending;pending=null;assertNotNull(callback);callback.onReceiveValue(JSONObject.quote(data.toString()));}
 }
 @Before public void setup()throws Exception{c=RuntimeEnvironment.getApplication();SessionState.prefs(c).edit().clear().commit();provider=new WebViewProfileProvider(c,CookieJar.parse("sessionid=FIXTURE; ds_user_id=123",System.currentTimeMillis()),(v,u)->{},u->null);}
 WebViewProfileProvider.Operation operation(){WebViewProfileProvider.Operation o=provider.new Operation("example","123",false);o.view=new Page(c);o.started=true;o.deadline=SystemClock.uptimeMillis()+45000;return o;}
 JSONObject image()throws Exception{return new WebProfileTest().valid();}
 @Test public void deadlineAndDelayedJsCallbackCannotBecomeNoImageOrSuccess()throws Exception{
  WebViewProfileProvider.Operation o=operation();Page page=(Page)o.view;o.poll();ShadowSystemClock.advanceBy(Duration.ofSeconds(45));page.emit(image());assertEquals("WEBVIEW_TIMEOUT",o.failure.code);assertFalse(FailurePolicy.noImage(o.failure));assertEquals(Diagnostics.Reason.DEADLINE,o.reason);
 }
 @Test public void lateLoadingImageSucceedsBeforeDeadline()throws Exception{
  WebViewProfileProvider.Operation o=operation();Page page=(Page)o.view;o.poll();page.emit(new JSONObject().put("reason","PAGE_NOT_READY").put("candidateFound",true));Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));page.emit(image());assertNull(o.failure);assertNotNull(o.result);
 }
 @Test public void repeatedErrorPage200IsRetryableAndPausePreservesNextOperation()throws Exception{
  WebViewProfileProvider.Operation o=operation();Page page=(Page)o.view;o.poll();page.emit(new JSONObject().put("wall","PROFILE_PAGE_UNAVAILABLE").put("reason","ROUTED_ERROR").put("pageReady",true).put("searchCompleted",true));assertFalse(FailurePolicy.unavailable(o.failure));assertTrue(FailurePolicy.retry(o.failure));
  WebViewProfileProvider.Operation paused=operation();Page old=(Page)paused.view;paused.poll();paused.fail(new ProfileProvider.Failure("REQUEST_CANCELLED"));old.emit(image());assertNull(paused.result);
  WebViewProfileProvider.Operation resumed=operation();Page current=(Page)resumed.view;resumed.poll();current.emit(image());assertNull(resumed.failure);assertNotNull(resumed.result);
 }
 @Test public void stalledJsCallbackIsStoppedByOperationWatchdog(){WebViewProfileProvider.Operation o=operation();o.armWatchdog();o.poll();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(57));assertEquals("WEBVIEW_TIMEOUT",o.failure.code);assertFalse(FailurePolicy.noImage(o.failure));}
 WebResourceRequest request(boolean main){return new WebResourceRequest(){public android.net.Uri getUrl(){return android.net.Uri.parse("https://www.instagram.com/example/");}public boolean isForMainFrame(){return main;}public boolean isRedirect(){return false;}public boolean hasGesture(){return false;}public String getMethod(){return "GET";}public java.util.Map<String,String> getRequestHeaders(){return java.util.Collections.emptyMap();}};}
 @Test public void main404StillWaitsForExplicitText()throws Exception{
  org.robolectric.util.ReflectionHelpers.setField(provider,"verifiedSession",true);
  WebViewProfileProvider.Operation o=operation();Page page=(Page)o.view;
  o.client().onReceivedHttpError(page,request(true),new WebResourceResponse("text/html","UTF-8",404,"Not Found",java.util.Collections.emptyMap(),new java.io.ByteArrayInputStream(new byte[0])));
  assertNull(o.failure);o.poll();page.emit(new JSONObject().put("wall","PROFILE_PAGE_UNAVAILABLE").put("reason","TEXT_MATCH").put("unavailableText",true).put("pageReady",true).put("searchCompleted",true));assertTrue(FailurePolicy.unavailable(o.failure));assertEquals(404,o.failure.http);
 }
 @Test public void http404AloneDoesNotOverrideValidImageAndImage404IsNotProfileDeletion()throws Exception{
  for(boolean main:new boolean[]{false,true}){WebViewProfileProvider.Operation o=operation();Page page=(Page)o.view;o.client().onReceivedHttpError(page,request(main),new WebResourceResponse("text/html","UTF-8",404,"Not Found",java.util.Collections.emptyMap(),new java.io.ByteArrayInputStream(new byte[0])));assertNull(o.failure);o.poll();page.emit(image());assertNull(o.failure);assertNotNull(o.result);}
 }
 @Test public void transientNetworkFailureAndScriptMemoryLimitAreNotPermanentAbsence(){
  WebViewProfileProvider.Operation o=operation();o.client().onReceivedError(o.view,request(true),null);assertEquals("WEBVIEW_NETWORK_ERROR",o.failure.code);assertTrue(FailurePolicy.retry(o.failure));assertFalse(FailurePolicy.unavailable(o.failure));
  WebViewProfileProvider.Operation memory=operation();((Page)memory.view).memory=true;memory.poll();assertEquals("IMAGE_MEMORY_LIMIT",memory.failure.code);assertTrue(FailurePolicy.pause(memory.failure));assertEquals("IMAGE_MEMORY_LIMIT",provider.blockingFailure().code);
 }
 @Test public void cleanupErrorCannotRewriteDeadlineAsAnotherProfileResult(){WebViewProfileProvider.Operation o=operation();((Page)o.view).brokenReset=true;o.fail(new ProfileProvider.Failure("WEBVIEW_TIMEOUT"));assertEquals("WEBVIEW_TIMEOUT",o.failure.code);assertEquals("WEBVIEW_CLEANUP_FAILED",provider.blockingFailure().code);assertEquals(0,o.done.getCount());}
 @Test public void explicitEnglishAndTurkishTextFinishOnFirstPollAndExportShortDiagnostics()throws Exception{
  for(String phrase:new String[]{"Sorry, this page isn't available", "Bu sayfaya ulaşılamıyor"}){
   WebViewProfileProvider.Operation o=operation();Page page=(Page)o.view;long began=SystemClock.uptimeMillis();o.poll();
   // DOM text matching is exercised in the browser fixtures; this models its fixed, private result.
   page.emit(new JSONObject().put("wall","PROFILE_PAGE_UNAVAILABLE").put("reason","TEXT_MATCH").put("unavailableText",true).put("pageReady",true).put("searchCompleted",true).put("candidateFound",false));
   assertEquals("PROFILE_PAGE_UNAVAILABLE",o.failure.code);assertTrue(o.failure.confirmedUnavailable);assertTrue(FailurePolicy.unavailable(o.failure));assertEquals(0,o.done.getCount());assertTrue(SystemClock.uptimeMillis()-began<1000);
   assertEquals(0,o.http);assertEquals("UNKNOWN",o.sessionEvidence);
  }
  java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();new Diagnostics(c).export(out);String text=out.toString("UTF-8");
  assertTrue(text.contains("code=PROFILE_PAGE_UNAVAILABLE reason=TEXT_MATCH"));assertTrue(text.contains("elapsedMs=0"));assertFalse(text.contains("WEBVIEW_TIMEOUT"));assertFalse(text.contains("example"));assertFalse(text.contains("Bu sayfaya"));
  System.out.println("FAST_SKIP_FIXTURE_DIAGNOSTICS_BEGIN\n"+text+"FAST_SKIP_FIXTURE_DIAGNOSTICS_END");
 }
 @Test public void candidateSnapshotKeepsPollingInsteadOfSkipping()throws Exception{
  WebViewProfileProvider.Operation o=operation();Page page=(Page)o.view;o.poll();page.emit(new JSONObject().put("wall","PROFILE_PAGE_UNAVAILABLE").put("reason","TEXT_MATCH").put("unavailableText",true).put("candidateFound",true));assertNull(o.failure);assertFalse(o.finishing);
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));page.emit(image());assertNull(o.failure);assertNotNull(o.result);
 }
}
