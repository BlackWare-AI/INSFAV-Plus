package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = {28, 34},
    manifest = Config.NONE)
public class ProfileOutcomeHistoryTest {
  Context c;

  @Before
  public void setup() {
    c = RuntimeEnvironment.getApplication();
    c.deleteDatabase("fetcher-queue.db");
  }

  @Test
  public void allProfileFailuresSurviveReopenStartResumeAndJsonRemovalWithoutHidingNewUsers() {
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("gone", "noimage", "timeout", "failed"));
      q.mark("gone", "SKIPPED_UNAVAILABLE", "PROFILE_PAGE_UNAVAILABLE");
      q.mark("noimage", "SKIPPED_NO_IMAGE", "PROFILE_DEFAULT_AVATAR");
      q.attempted("timeout");
      q.mark("timeout", "RETRY_LATER", "WEBVIEW_TIMEOUT");
      q.attempted("failed");
      q.mark("failed", "FAILED", "PROFILE_PROCESSING_ERROR");
      assertEquals(4, q.recordedOutcomes());
      q.sync(Collections.emptyList());
      assertTrue(q.counts().isEmpty());
      assertEquals(4, q.recordedOutcomes());
    }
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("gone", "noimage", "timeout", "failed", "new", "@NEW"));
      q.prepareMissing();
      q.recoverInterrupted();
      assertEquals(Collections.singletonList("new"), q.eligible());
      assertEquals(5, q.users(false).size());
      assertEquals(1, q.held());
      assertFalse(q.claim("timeout"));
      assertFalse(q.claim("gone"));
      assertEquals(1, q.attempts("timeout"));
      assertEquals(Integer.valueOf(1), q.counts().get("FAILED"));
      q.retrySkipped();
      assertEquals(5, q.eligible().size());
      assertEquals(0, q.recordedOutcomes());
      assertEquals(0, q.attempts("timeout"));
    }
  }

  @Test
  public void interruptedClaimsAndSessionWaitAreNotRecordedAsFailedProfiles() {
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("interrupted", "auth"));
      assertTrue(q.claim("interrupted"));
      q.deferClaims("REQUEST_CANCELLED");
      q.mark("auth", "RETRY_LATER", "COOKIE_AUTH_UNVERIFIED");
      q.recoverInterrupted();
      assertEquals(2, q.eligible().size());
      assertEquals(0, q.held());
      assertEquals(0, q.recordedOutcomes());
    }
  }

  @Test
  public void explicitRetryDoesNotForgetAbsentProfilesOrTouchSuccessfulFiles() {
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("absent", "present", "done"));
      q.mark("absent", "SKIPPED_UNAVAILABLE", "PROFILE_PAGE_UNAVAILABLE");
      q.mark("present", "SKIPPED_NO_IMAGE", "PROFILE_DEFAULT_AVATAR");
      q.mark("done", "SUCCESS", "VALID_WEBP");
      q.sync(Arrays.asList("present", "done"));
      q.retrySkipped();
      assertEquals(1, q.recordedOutcomes());
      assertEquals(Collections.singletonList("present"), q.eligible());
      q.sync(Arrays.asList("absent", "present", "done", "fresh"));
      assertEquals(Arrays.asList("fresh", "present"), q.eligible());
      assertEquals(Integer.valueOf(1), q.counts().get("SUCCESS"));
    }
  }

  @Test
  public void versionFourUpgradeImportsHistoryWithoutResettingStatusesAttemptsOrImageIndex() {
    try (SQLiteDatabase db = c.openOrCreateDatabase("fetcher-queue.db", 0, null)) {
      db.execSQL(
          "CREATE TABLE accounts(username TEXT PRIMARY KEY,position INTEGER,status TEXT,code"
              + " TEXT,eligible INTEGER DEFAULT 1,attempts INTEGER DEFAULT 0,phase TEXT DEFAULT"
              + " 'PENDING')");
      db.execSQL(
          "INSERT INTO accounts(username,position,status,code,attempts)"
              + " VALUES('gone',0,'SKIPPED_UNAVAILABLE','PROFILE_PAGE_UNAVAILABLE',0),('late',1,'RETRY_LATER','WEBVIEW_TIMEOUT',2),('done',2,'SUCCESS','VALID_WEBP',0)");
      db.setVersion(4);
    }
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(2, q.recordedOutcomes());
      assertEquals(2, q.attempts("late"));
      assertEquals(Integer.valueOf(1), q.counts().get("SUCCESS"));
      assertTrue(q.eligible().isEmpty());
      q.sync(Collections.emptyList());
      q.sync(Arrays.asList("gone", "late", "fresh"));
      assertEquals(Collections.singletonList("fresh"), q.eligible());
      assertEquals(2, q.attempts("late"));
    }
  }

  @Test
  public void ledgerWriteFailureRollsBackAccountStatusInsteadOfLosingHistory() {
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Collections.singletonList("fault"));
      q.getWritableDatabase()
          .execSQL(
              "CREATE TRIGGER reject_outcome BEFORE INSERT ON fetch_outcomes BEGIN SELECT"
                  + " RAISE(ABORT,'fixture'); END");
      try {
        q.mark("fault", "SKIPPED_UNAVAILABLE", "PROFILE_PAGE_UNAVAILABLE");
        fail("Expected SQLite rejection");
      } catch (android.database.sqlite.SQLiteException expected) {
      }
      assertEquals(Collections.singletonList("fault"), q.eligible());
      assertEquals(0, q.recordedOutcomes());
      assertNull(q.counts().get("SKIPPED_UNAVAILABLE"));
    }
  }
}
