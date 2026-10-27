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
  public void timeoutPersistsClaimsAndRemovesDownloadNotification() {
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
    assertNull("A stopped service must not leave a download notification", n);
  }

  @Test
  public void manualResumeHonorsPersistedSystemDeferral() throws Exception {
    Context c = org.robolectric.RuntimeEnvironment.getApplication();
    int minute =
        java.time.ZonedDateTime.now().getHour() * 60 + java.time.ZonedDateTime.now().getMinute();
    long until = System.currentTimeMillis() + 60000;
    SessionState.prefs(c)
        .edit()
        .clear()
        .putInt("startMinute", (minute + 1430) % 1440)
        .putInt("endMinute", (minute + 10) % 1440)
        .putLong("systemNotBefore", until)
        .commit();
    java.io.IOException error =
        assertThrows(java.io.IOException.class, () -> Fetcher.action("resume"));
    assertTrue(error.getMessage().contains("Android background restrictions"));
    assertEquals(until, SessionState.prefs(c).getLong("systemNotBefore", 0));
    assertFalse(SessionState.prefs(c).getBoolean("armed", false));
  }
}
