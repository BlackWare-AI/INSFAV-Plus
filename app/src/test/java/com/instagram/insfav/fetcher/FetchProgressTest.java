package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.content.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class FetchProgressTest {
  SharedPreferences p;

  @Before
  public void setup() {
    p = SessionState.prefs(RuntimeEnvironment.getApplication());
    p.edit().clear().putString("state", "Downloading").commit();
    QueueControl.runProcessed = 5;
    QueueControl.lastResult = "WEBVIEW_TIMEOUT";
  }

  @Test
  public void fiveResultsShowWorkerDelayAndCountdownInsteadOfAStall() throws Exception {
    JSONObject result = FetchProgress.snapshot(p, true, 0, 5, false, 26000, 1000);
    assertEquals("Waiting for configured worker delay", result.getString("phase"));
    assertEquals(25, result.getLong("waitSeconds"));
    assertEquals(5, result.getInt("handledThisRun"));
    assertEquals("WEBVIEW_TIMEOUT", result.getString("lastResult"));
    assertEquals(
        1, FetchProgress.snapshot(p, true, 0, 5, false, 26000, 25999).getLong("waitSeconds"));
    assertEquals(
        0, FetchProgress.snapshot(p, true, 0, 5, false, 26000, 26000).getLong("waitSeconds"));
  }

  @Test
  public void authenticationLoadingAndRealGlobalPauseRemainDistinct() throws Exception {
    assertEquals(
        "Verifying Instagram session",
        FetchProgress.snapshot(p, true, 0, 5, true, 5000, 1000).getString("phase"));
    assertEquals(
        "Loading profiles / downloading images",
        FetchProgress.snapshot(p, true, 5, 0, false, 5000, 1000).getString("phase"));
    p.edit().putString("blocked", "RATE_LIMITED").putString("state", "Rate Limited").commit();
    JSONObject paused = FetchProgress.snapshot(p, true, 0, 5, false, 5000, 1000);
    assertEquals("Rate Limited", paused.getString("phase"));
    assertEquals(0, paused.getLong("waitSeconds"));
  }

  @Test
  public void stoppedViewDoesNotInventActiveWorkOrExposeAccountIdentifier() throws Exception {
    QueueControl.current = "private.fixture";
    JSONObject result = FetchProgress.snapshot(p, false, 0, 0, false, 5000, 1000);
    assertEquals("Downloading", result.getString("phase"));
    assertEquals(0, result.getLong("waitSeconds"));
    assertFalse(result.toString().contains("private.fixture"));
  }
}
