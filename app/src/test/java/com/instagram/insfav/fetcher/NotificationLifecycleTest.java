package com.instagram.insfav.fetcher;
import android.content.*;import android.app.Notification;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class NotificationLifecycleTest {
 Context c;
 @Before public void setup(){c=RuntimeEnvironment.getApplication();c.deleteDatabase("fetcher-queue.db");SessionState.prefs(c).edit().clear().commit();}
 @Test public void completedNotificationExpiresButFatalErrorRemains(){Notification done=DownloadNotification.create(c,"Completed");assertEquals(6000,done.getTimeoutAfter());SessionState.prefs(c).edit().putString("blocked","PROCESSING_ERROR").commit();Notification failed=DownloadNotification.create(c,"Paused: processing error");assertEquals(0,failed.getTimeoutAfter());assertTrue(failed.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Failed"));}
 @Test public void realRateLimitCarriesCooldownAndDoesNotLookLikeUserPause(){SessionState.prefs(c).edit().putString("blocked","RATE_LIMITED").putInt("blockedHttp",429).putLong("retryAt",System.currentTimeMillis()+60000).putBoolean("background",true).putBoolean("armed",true).putBoolean("userPaused",false).commit();Notification rate=DownloadNotification.create(c,"Rate Limited");assertTrue(rate.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("HTTP 429"));assertTrue(rate.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Automatic resume"));assertEquals(0,rate.getTimeoutAfter());assertTrue(WorkScheduler.enabled(SessionState.prefs(c)));}
 @Test public void savedTimeoutsFinishClearlyWithoutDebugCountersOrPermanentWaiting(){
  try(QueueStore q=new QueueStore(c)){q.sync(java.util.Arrays.asList("done","later"));q.mark("done","SUCCESS","VALID_WEBP");q.attempted("later");q.mark("later","RETRY_LATER","WEBVIEW_TIMEOUT");}
  Notification n=DownloadNotification.create(c,"Finished: previous failures saved; use Retry to try again");
  String compact=n.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),detail=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString();
  assertEquals("Completed — Retry available · Downloaded 1",compact);assertTrue(detail.contains("Remaining 0"));assertTrue(detail.contains("1 saved for manual Retry"));
  assertFalse(detail.contains("WebViews"));assertFalse(detail.contains("accounts/min"));assertFalse(detail.contains("ETA"));assertFalse(detail.contains("later"));
  assertNull(n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT));assertEquals(6000,n.getTimeoutAfter());assertEquals(2,n.extras.getInt(Notification.EXTRA_PROGRESS));
 }
}
