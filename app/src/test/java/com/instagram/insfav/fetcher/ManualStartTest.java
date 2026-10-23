package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class ManualStartTest {
  Context c;

  @Before
  public void setup() {
    c = RuntimeEnvironment.getApplication();
    c.deleteDatabase("fetcher-queue.db");
    SessionState.prefs(c).edit().clear().commit();
    QueueControl.lock.set(false);
    FetchService.running = false;
  }

  @Test
  public void newSessionDisarmsSavedWorkAndPreservesQueue() {
    SessionState.prefs(c).edit().putBoolean("armed", true).putBoolean("userPaused", false)
        .putBoolean("background", true).commit();
    try (QueueStore q = new QueueStore(c)) {
      q.sync(java.util.Arrays.asList("finished", "pending"));
      q.mark("finished", "SUCCESS", "VALID_WEBP");
    }
    Fetcher.initialize(c, new Fetcher.Host() {
      public java.util.Collection<String> users() { return java.util.Collections.emptyList(); }
      public String tree() { return ""; }
      public String directory() { return ""; }
      public long revision() { return 0; }
      public boolean hasImage(String user) { return false; }
      public void imageWritten(String user, android.net.Uri uri, String tree) {}
    });
    assertFalse(SessionState.userStarted(c));
    assertFalse(WorkScheduler.enabled(SessionState.prefs(c)));
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(java.util.Arrays.asList("pending"), q.pending());
      assertEquals(Integer.valueOf(1), q.counts().get("SUCCESS"));
    }
    SessionState.prefs(c).edit().putBoolean("armed", true).putBoolean("userPaused", false).commit();
    assertTrue(SessionState.userStarted(c));
  }

  @Test
  public void queuedIntentsCannotStartNotificationBeforeManualStartOrAfterPause() {
    FetchService service = Robolectric.buildService(FetchService.class).create().get();
    for (boolean paused : new boolean[] {false, true}) {
      SessionState.prefs(c).edit().putBoolean("armed", paused).putBoolean("userPaused", paused).commit();
      for (String action : new String[] {"missing", "resume", "retry_skipped"}) {
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(new Intent(c, FetchService.class).setAction(action), 0, 1));
        assertFalse(QueueControl.lock.get());
        assertFalse(FetchService.running);
      }
    }
    assertEquals(0, c.getSystemService(NotificationManager.class).getActiveNotifications().length);
  }

  @Test
  public void androidStickyRestartNeverReplaysResumeEvenWhenPreviouslyArmed() {
    SessionState.prefs(c).edit().putBoolean("armed", true).putBoolean("userPaused", false)
        .putBoolean("background", true).commit();
    FetchService service = Robolectric.buildService(FetchService.class).create().get();
    assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1));
    assertFalse(FetchService.running);
    assertFalse(QueueControl.lock.get());
  }
}
