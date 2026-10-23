package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.content.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = {28, 34},
    manifest = Config.NONE)
public class IntegrationQueueTest {
  Context c;

  @Before
  public void setup() {
    c = RuntimeEnvironment.getApplication();
    c.deleteDatabase("fetcher-queue.db");
    SessionState.prefs(c).edit().clear().commit();
  }

  @Test
  public void deduplicatesAndPreservesDurableStatuses() {
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("one", "one", "two", "three"));
      q.mark("one", "SUCCESS", "VALID_WEBP");
      q.mark("two", "SKIPPED_UNAVAILABLE", "PROFILE_NOT_FOUND_OR_UNAVAILABLE");
      q.mark("three", "RETRY_LATER", "NETWORK_ERROR");
      q.attempted("three");
    }
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("one", "two", "three"));
      assertEquals(3, q.users(false).size());
      assertEquals(Integer.valueOf(1), q.counts().get("SUCCESS"));
      assertEquals(Integer.valueOf(1), q.counts().get("SKIPPED_UNAVAILABLE"));
      assertTrue(q.eligible().isEmpty());
      assertEquals(1, q.held());
    }
  }

  @Test
  public void missingUrlIsRetryNotDeletedAndCannotBlockWholeQueue() {
    assertTrue(
        FailurePolicy.retry(
            new ProfileProvider.Failure("PROFILE_IMAGE_URL_MISSING", 200, 0, false)));
    assertFalse(
        FailurePolicy.noImage(
            new ProfileProvider.Failure("PROFILE_IMAGE_URL_MISSING", 200, 0, false)));
    assertFalse(
        FailurePolicy.unavailable(
            new ProfileProvider.Failure("PROFILE_IMAGE_URL_MISSING", 200, 0, false)));
    assertFalse(
        FailurePolicy.pause(
            new ProfileProvider.Failure("PROFILE_IMAGE_URL_MISSING", 200, 0, false)));
    assertFalse(
        FailurePolicy.pause(new ProfileProvider.Failure("WEBVIEW_NETWORK_ERROR", 0, 0, true)));
    assertFalse(
        FailurePolicy.pause(new ProfileProvider.Failure("WEBVIEW_NAVIGATION_BLOCKED", 0, 0, true)));
    assertFalse(
        FailurePolicy.pause(new ProfileProvider.Failure("PROFILE_PAGE_UNAVAILABLE", 200, 0, true)));
  }

  @Test
  public void confirmedUnavailableAdvancesAndRetriesAreBounded() {
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("gone", "later", "good"));
      q.mark("gone", "SKIPPED_UNAVAILABLE", "PROFILE_NOT_FOUND_OR_UNAVAILABLE");
      q.mark("later", "RETRY_LATER", "NETWORK_ERROR");
      for (int i = 0; i < 3; i++) q.attempted("later");
      assertEquals(Arrays.asList("good"), q.eligible());
    }
    assertTrue(
        FailurePolicy.unavailable(
            new ProfileProvider.Failure("PROFILE_NOT_FOUND_OR_UNAVAILABLE", 404, 0, false, true)));
  }

  @Test
  public void globalSessionAndRateLimitBlocksStayGlobal() {
    for (ProfileProvider.Failure e :
        new ProfileProvider.Failure[] {
          new ProfileProvider.Failure("RATE_LIMITED", 429, 100, true),
          new ProfileProvider.Failure("COOKIE_INVALID_EXPIRED", 401, 0, true),
          new ProfileProvider.Failure("CHALLENGE_REQUIRED", 200, 0, true),
          new ProfileProvider.Failure("PRIVATE_RESTRICTED", 403, 0, true)
        }) assertTrue(FailurePolicy.pause(e));
  }

  @Test
  public void freshStartNeedsNoThreeProfileProof() {
    SharedPreferences p = SessionState.prefs(c);
    p.edit()
        .putBoolean("background", true)
        .putBoolean("armed", true)
        .putBoolean("userPaused", false)
        .commit();
    assertTrue(WorkScheduler.enabled(p));
    assertTrue(p.getString("cookieGeneration", "").isEmpty());
  }

  @Test
  public void startMissingRecoversLostSuccessfulOutputButLeavesUnavailableAlone() {
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("one", "two"));
      q.mark("one", "SUCCESS", "VALID_WEBP");
      q.mark("two", "SKIPPED_UNAVAILABLE", "PROFILE_NOT_FOUND_OR_UNAVAILABLE");
      q.prepareMissing();
      q.requeueLost("one");
      assertEquals(Arrays.asList("one"), q.eligible());
    }
  }

  @Test
  public void pauseAndStopPersistWithoutDiscardingCompletedOrPendingWork() {
    SharedPreferences p = SessionState.prefs(c);
    p.edit()
        .putBoolean("armed", true)
        .putBoolean("background", true)
        .putBoolean("userPaused", false)
        .commit();
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("finished", "next"));
      q.mark("finished", "SUCCESS", "VALID_WEBP");
    }
    QueueControl.pause(c, false);
    assertTrue(p.getBoolean("armed", false));
    assertTrue(p.getBoolean("userPaused", false));
    assertFalse(WorkScheduler.enabled(p));
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(Arrays.asList("next"), q.pending());
      assertEquals(Integer.valueOf(1), q.counts().get("SUCCESS"));
    }
    p.edit().putBoolean("userPaused", false).commit();
    assertTrue(WorkScheduler.enabled(p));
    QueueControl.pause(c, true);
    assertFalse(p.getBoolean("armed", true));
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(Arrays.asList("next"), q.pending());
    }
  }

  @Test
  public void startMissingResumeAndJsonDeltaPreserveAllHistoryAndImageMetadata() {
    List<String> names =
        Arrays.asList("done", "existing", "failed", "gone", "empty", "later", "@DONE");
    try (QueueStore q = new QueueStore(c)) {
      q.sync(names);
      q.mark("done", "SUCCESS", "VALID_WEBP");
      q.mark("existing", "EXISTING", "LOCAL_IMAGE");
      q.mark("failed", "FAILED", "PROFILE_PROCESSING_ERROR");
      q.attempted("failed");
      q.attempted("failed");
      q.mark("gone", "SKIPPED_UNAVAILABLE", "PROFILE_PAGE_UNAVAILABLE");
      q.mark("empty", "SKIPPED_NO_IMAGE", "PROFILE_DEFAULT_AVATAR");
      q.mark("later", "RETRY_LATER", "WEBVIEW_TIMEOUT");
      q.attempted("later");
      q.image(
          new QueueStore.Image(
              "done", "content://fixture/done", "done.webp", 100, 128, 128, 0, true));
      q.meta("ready", "1");
    }
    Map<String, Integer> before;
    try (QueueStore q = new QueueStore(c)) {
      before = q.counts();
      q.prepareMissing();
      q.recoverInterrupted();
      q.sync(names);
      assertEquals(before, q.counts());
      assertEquals(2, q.attempts("failed"));
      assertEquals(1, q.attempts("later"));
      assertEquals(6, q.users(false).size());
      q.sync(Arrays.asList("done", "existing", "failed", "gone", "empty", "later", "new", "NEW"));
      assertEquals(7, q.users(false).size());
      assertEquals(128, q.image("done").width);
      assertEquals(Integer.valueOf(1), q.counts().get("SUCCESS"));
      assertEquals(Integer.valueOf(1), q.counts().get("FAILED"));
      q.sync(Arrays.asList("existing", "failed", "gone", "empty", "later", "new"));
      assertNotNull("Removed JSON entries do not delete image metadata or files", q.image("done"));
      assertEquals(2, q.attempts("failed"));
      assertEquals(Integer.valueOf(1), q.counts().get("SKIPPED_UNAVAILABLE"));
      assertEquals(1, q.attempts("later"));
    }
  }
}
