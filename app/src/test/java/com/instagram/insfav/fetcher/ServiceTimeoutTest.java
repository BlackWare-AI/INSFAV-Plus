package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ServiceTimeoutTest {
  @Test
  public void timeoutPersistsClaimsAndPublishesNonDownloadingResult() {
    FetchService service = Robolectric.buildService(FetchService.class).create().get();
    Context c = service.getApplicationContext();
    SessionState.prefs(c).edit().clear().commit();
    try (QueueStore q = new QueueStore(c)) {
      q.sync(java.util.Arrays.asList("complete", "interrupted"));
      q.mark("complete", "SUCCESS", "VALID_WEBP");
      q.claim("interrupted");
    }
    service.onTimeout(7, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    assertTrue(SessionState.prefs(c).getLong("systemNotBefore", 0) > System.currentTimeMillis());
    assertTrue(SessionState.prefs(c).getString("state", "").startsWith("Paused:"));
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(Integer.valueOf(1), q.counts().get("SUCCESS"));
      assertEquals(Integer.valueOf(1), q.counts().get("RETRY_LATER"));
    }
    Notification n =
        Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(3);
    assertNotNull(n);
    assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("Paused"));
    assertEquals(0, n.flags & Notification.FLAG_ONGOING_EVENT);
  }
}
