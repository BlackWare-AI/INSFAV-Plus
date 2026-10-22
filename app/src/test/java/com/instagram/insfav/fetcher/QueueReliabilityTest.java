package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class QueueReliabilityTest {
  Context c;
  CountingFixture storage;
  Uri tree = Uri.parse("content://fetcher.test/tree/root");
  File folder;
  List<String> users = new ArrayList<>(),
      requests = Collections.synchronizedList(new ArrayList<>());
  byte[] png;
  String fault = "", writeFault = "";
  ProfileProvider.Failure authFailure;
  int authCalls;

  public static class CountingFixture extends FetcherStorageTest.Fixture {
    int fullListings, targetedLookups;

    @Override
    public android.database.Cursor queryChildDocuments(
        String parent, String[] projection, android.os.Bundle args) {
      if (args != null && args.containsKey(ContentResolver.QUERY_ARG_SQL_SELECTION))
        targetedLookups++;
      else fullListings++;
      return super.queryChildDocuments(parent, projection, (String) null);
    }
  }

  @Before
  public void setup() throws Exception {
    c = RuntimeEnvironment.getApplication();
    c.deleteDatabase("fetcher-queue.db");
    QueueControl.requested = false;
    QueueControl.lock.set(false);
    QueueControl.memoryPressure = false;
    SessionState.prefs(c)
        .edit()
        .clear()
        .putInt("minimum", 1)
        .putInt("maximum", 1)
        .putInt("startMinute", 0)
        .putInt("endMinute", 1439)
        .putString("tree", tree.toString())
        .putString("directoryId", "root/.thumbnails")
        .commit();
    ProviderInfo info = new ProviderInfo();
    info.authority = "fetcher.test";
    info.exported = true;
    info.grantUriPermissions = true;
    info.readPermission = "android.permission.MANAGE_DOCUMENTS";
    info.writePermission = info.readPermission;
    storage = Robolectric.buildContentProvider(CountingFixture.class).create(info).get();
    storage.root = Files.createTempDirectory("queue-reliability").toFile();
    folder = new File(storage.root, ".thumbnails");
    folder.mkdir();
    c.getContentResolver()
        .takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    png = CodecTest.png(320, 320);
    Fetcher.initialize(
        c,
        new Fetcher.Host() {
          public Collection<String> users() {
            return users;
          }

          public String tree() {
            return tree.toString();
          }

          public String directory() {
            return "root/.thumbnails";
          }

          public long revision() {
            return 0;
          }

          public boolean hasImage(String u) {
            throw new AssertionError("No per-file validation");
          }

          public void imageWritten(String u, Uri uri, String t) {
            if (u.equals("bad")) {
              switch (writeFault) {
                case "RUNTIME":
                  throw new IllegalArgumentException();
                case "SECURITY":
                  throw new SecurityException();
                case "DATABASE":
                  throw new android.database.sqlite.SQLiteException();
                case "MEMORY":
                  throw new OutOfMemoryError("fixture");
              }
            }
          }
        });
    FetchBroker broker =
        new FetchBroker(
            c,
            full ->
                new ProfileProvider() {
                  public void authenticate() throws Failure {
                    authCalls++;
                    if (authFailure != null) throw authFailure;
                  }

                  public byte[] fetch(String u) throws Failure {
                    requests.add(u);
                    if (fault.equals("FIRST_FIVE_TIMEOUT") && u.startsWith("timeout"))
                      throw new Failure("WEBVIEW_TIMEOUT");
                    if (u.equals("bad")) {
                      if (fault.equals("RUNTIME")) throw new IllegalStateException("fixture");
                      if (fault.equals("DATABASE"))
                        throw new android.database.sqlite.SQLiteException("fixture");
                      if (fault.equals("SECURITY")) throw new SecurityException("fixture");
                      if (fault.equals("MEMORY")) throw new OutOfMemoryError("fixture");
                      if (fault.equals("TIMEOUT")) throw new Failure("WEBVIEW_TIMEOUT");
                      if (fault.equals("FAST_UNAVAILABLE"))
                        throw new Failure("PROFILE_PAGE_UNAVAILABLE", 0, 0, false, true);
                    }
                    return png.clone();
                  }

                  public void cancel() {}
                },
            new TestTime());
    org.robolectric.util.ReflectionHelpers.setStaticField(FetchBroker.class, "instance", broker);
  }

  @After
  public void cleanup() {
    FetchBroker.get(c).cancelAll();
    FetchBroker.get(c).endBatch();
  }

  void run(String action, String... names) {
    if (names.length > 0) {
      users = Arrays.asList(names);
      try (QueueStore q = new QueueStore(c)) {
        q.sync(users);
      }
    }
    new QueueEngine(c, s -> {}, () -> false).run(action, false);
  }

  String status(QueueStore q, String user) {
    try (android.database.Cursor cursor =
        q.getReadableDatabase()
            .rawQuery("SELECT status FROM accounts WHERE username=?", new String[] {user})) {
      assertTrue(cursor.moveToFirst());
      return cursor.getString(0);
    }
  }

  @Test(timeout = 15000)
  public void providerRuntimeFailureAffectsOnlyOneProfile() {
    fault = "RUNTIME";
    run("missing", "bad", "good");
    assertEquals(Arrays.asList("bad", "good"), requests);
    assertEquals("", SessionState.prefs(c).getString("blocked", ""));
    try (QueueStore q = new QueueStore(c)) {
      assertEquals("FAILED", status(q, "bad"));
      assertEquals("SUCCESS", status(q, "good"));
      assertEquals(1, q.attempts("bad"));
      assertTrue(q.failures().containsKey("PROFILE_PROCESSING_ERROR"));
    }
  }

  @Test(timeout = 15000)
  public void coordinatorRuntimeFailureDoesNotStopFollowingProfile() {
    writeFault = "RUNTIME";
    run("missing", "bad", "good");
    assertEquals(Arrays.asList("bad", "good"), requests);
    try (QueueStore q = new QueueStore(c)) {
      assertEquals("FAILED", status(q, "bad"));
      assertEquals("SUCCESS", status(q, "good"));
    }
    assertEquals("", SessionState.prefs(c).getString("blocked", ""));
  }

  @Test(timeout = 15000)
  public void timeoutIsHeldForExplicitRetryAndFollowingProfileSucceeds() {
    fault = "TIMEOUT";
    run("missing", "bad", "good");
    assertEquals(Arrays.asList("bad", "good"), requests);
    try (QueueStore q = new QueueStore(c)) {
      assertEquals("RETRY_LATER", status(q, "bad"));
      assertEquals(1, q.attempts("bad"));
      assertEquals("SUCCESS", status(q, "good"));
      assertNull(q.counts().get("SKIPPED_NO_IMAGE"));
      assertNull(q.counts().get("SKIPPED_UNAVAILABLE"));
    }
  }

  @Test(timeout = 15000)
  public void memoryPausePreservesSuccessAndResumeDoesNotDownloadItAgain() throws Exception {
    fault = "MEMORY";
    run("missing", "done", "bad", "next");
    assertEquals(Arrays.asList("done", "bad"), requests);
    assertEquals("IMAGE_MEMORY_LIMIT", SessionState.prefs(c).getString("blocked", ""));
    byte[] saved = Files.readAllBytes(new File(folder, "done.webp").toPath());
    int listings = storage.fullListings, targeted = storage.targetedLookups;
    try (QueueStore q = new QueueStore(c)) {
      assertEquals("SUCCESS", status(q, "done"));
      assertEquals("RETRY_LATER", status(q, "bad"));
      assertEquals("PENDING", status(q, "next"));
      assertEquals(0, q.attempts("bad"));
    }
    fault = "";
    SessionState.prefs(c).edit().putString("blocked", "").commit();
    run("resume");
    assertEquals(1, Collections.frequency(requests, "done"));
    assertEquals("Resume must not enumerate the folder", listings, storage.fullListings);
    assertEquals(
        "Only the remaining profiles need exact-name lookups",
        3,
        storage.targetedLookups - targeted);
    assertArrayEquals(saved, Files.readAllBytes(new File(folder, "done.webp").toPath()));
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(Integer.valueOf(3), q.counts().get("SUCCESS"));
    }
  }

  @Test(timeout = 15000)
  public void storageCommitFailurePausesWithoutConsumingProfileRetryBudget() {
    storage.rejectRename = true;
    run("missing", "bad", "next");
    assertEquals(Collections.singletonList("bad"), requests);
    String blocked = SessionState.prefs(c).getString("blocked", "");
    assertTrue(
        blocked, blocked.startsWith("OUTPUT_") || blocked.equals("ATOMIC_RENAME_UNSUPPORTED"));
    try (QueueStore q = new QueueStore(c)) {
      assertEquals("RETRY_LATER", status(q, "bad"));
      assertEquals(0, q.attempts("bad"));
      assertEquals("PENDING", status(q, "next"));
    }
  }

  @Test(timeout = 15000)
  public void storagePermissionLossStaysQueueLevel() {
    writeFault = "SECURITY";
    run("missing", "bad", "next");
    assertEquals("STORAGE_PERMISSION_LOST", SessionState.prefs(c).getString("blocked", ""));
    assertEquals(Collections.singletonList("bad"), requests);
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(0, q.attempts("bad"));
    }
  }

  @Test(timeout = 15000)
  public void databaseErrorStaysQueueLevel() {
    writeFault = "DATABASE";
    run("missing", "bad", "next");
    assertEquals("DATABASE_ERROR", SessionState.prefs(c).getString("blocked", ""));
    assertEquals(Collections.singletonList("bad"), requests);
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(0, q.attempts("bad"));
      assertEquals("RETRY_LATER", status(q, "bad"));
    }
  }

  @Test(timeout = 15000)
  public void coordinatorMemoryErrorStopsNewProfilesAndKeepsCommittedFile() {
    writeFault = "MEMORY";
    run("missing", "bad", "next");
    assertEquals("IMAGE_MEMORY_LIMIT", SessionState.prefs(c).getString("blocked", ""));
    assertEquals(Collections.singletonList("bad"), requests);
    assertTrue(new File(folder, "bad.webp").exists());
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(0, q.attempts("bad"));
      assertEquals("RETRY_LATER", status(q, "bad"));
    }
  }

  @Test(timeout = 15000)
  public void invalidSessionStopsBeforeAnyProfileAndPreservesQueue() {
    authFailure = new ProfileProvider.Failure("COOKIE_INVALID_EXPIRED", 401, 0, true);
    run("missing", "bad", "next");
    assertTrue(requests.isEmpty());
    assertEquals("UNVERIFIED", SessionState.evidence(SessionState.prefs(c)));
    assertEquals("Paused: session not verified", SessionState.prefs(c).getString("state", ""));
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(2, q.pending().size());
      assertEquals(0, q.attempts("bad"));
      assertNull(q.counts().get("SKIPPED_UNAVAILABLE"));
    }
  }

  @Test(timeout = 15000)
  public void temporaryAuthNetworkFailureWaitsThenReauthenticatesOnResume() throws Exception {
    authFailure = new ProfileProvider.Failure("WEBVIEW_NETWORK_ERROR");
    run("missing", "bad", "next");
    assertTrue(requests.isEmpty());
    assertEquals("", SessionState.prefs(c).getString("blocked", ""));
    assertEquals("UNKNOWN", SessionState.evidence(SessionState.prefs(c)));
    assertTrue(SessionState.prefs(c).getLong("retryAt", 0) > System.currentTimeMillis());
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(0, q.attempts("bad"));
      assertEquals(2, q.pending().size());
    }
    Files.write(new File(c.getFilesDir(), "fetcher-cookies.enc").toPath(), new byte[] {1});
    try {
      Fetcher.action("resume");
      fail("Cooldown must prevent a manual restart");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Retry cooldown"));
    }
    SessionState.prefs(c)
        .edit()
        .putBoolean("background", true)
        .putBoolean("armed", true)
        .putBoolean("userPaused", false)
        .commit();
    assertTrue(WorkScheduler.enabled(SessionState.prefs(c)));
    authFailure = null;
    SessionState.prefs(c).edit().putLong("retryAt", 0).commit();
    run("resume");
    assertEquals(2, requests.size());
    assertEquals(2, authCalls);
    assertEquals("VERIFIED", SessionState.evidence(SessionState.prefs(c)));
  }

  @Test(timeout = 15000)
  public void explicitTextPolicySkipsOnceAndImmediatelyContinues() {
    fault = "FAST_UNAVAILABLE";
    run("missing", "bad", "good");
    assertEquals(Arrays.asList("bad", "good"), requests);
    try (QueueStore q = new QueueStore(c)) {
      assertEquals("SKIPPED_UNAVAILABLE", status(q, "bad"));
      assertEquals(0, q.attempts("bad"));
      assertEquals("SUCCESS", status(q, "good"));
      assertNull(q.counts().get("RETRY_LATER"));
    }
  }

  @Test(timeout = 20000)
  public void fiveWorkerTimeoutsDoNotStopTheNextProfilesOrRepeatOnResume() {
    SessionState.prefs(c).edit().putInt("concurrentWebViews", 5).commit();
    fault = "FIRST_FIVE_TIMEOUT";
    run(
        "missing",
        "timeout0",
        "timeout1",
        "timeout2",
        "timeout3",
        "timeout4",
        "good0",
        "good1",
        "good2",
        "good3",
        "good4",
        "good5",
        "good6");
    assertEquals(12, requests.size());
    for (int i = 0; i < 5; i++) assertEquals(1, Collections.frequency(requests, "timeout" + i));
    assertEquals(12, QueueControl.runProcessed);
    assertEquals("", SessionState.prefs(c).getString("blocked", ""));
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(5, q.held());
      assertEquals(Integer.valueOf(7), q.counts().get("SUCCESS"));
      assertTrue(q.pending().isEmpty());
    }
    int listings = storage.fullListings, lookups = storage.targetedLookups;
    run("resume");
    run("missing");
    assertEquals(12, requests.size());
    assertEquals(listings, storage.fullListings);
    assertEquals(lookups, storage.targetedLookups);
    fault = "";
    run("retry_skipped");
    assertEquals(17, requests.size());
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(Integer.valueOf(12), q.counts().get("SUCCESS"));
      assertEquals(0, q.held());
      assertEquals(0, q.recordedOutcomes());
    }
  }

  @Test(timeout = 20000)
  public void skippedProfileIsRememberedAcrossJsonRemovalAndReadditionWhileNewAccountRuns() {
    fault = "FAST_UNAVAILABLE";
    run("missing", "bad", "good");
    int listings = storage.fullListings;
    run("missing", "good");
    run("missing", "bad", "good", "newcomer", "NEWCOMER");
    run("resume");
    assertEquals(Arrays.asList("bad", "good", "newcomer"), requests);
    assertEquals(listings, storage.fullListings);
    try (QueueStore q = new QueueStore(c)) {
      assertEquals("SKIPPED_UNAVAILABLE", status(q, "bad"));
      assertEquals(Integer.valueOf(2), q.counts().get("SUCCESS"));
      assertEquals(1, q.recordedOutcomes());
    }
  }

  @Test(timeout = 15000)
  public void providerDatabaseErrorPausesInsteadOfBecomingProfileFailure() {
    fault = "DATABASE";
    run("missing", "bad", "next");
    assertEquals("DATABASE_ERROR", SessionState.prefs(c).getString("blocked", ""));
    try (QueueStore q = new QueueStore(c)) {
      assertEquals("RETRY_LATER", status(q, "bad"));
      assertEquals("PENDING", status(q, "next"));
    }
  }

  @Test(timeout = 15000)
  public void providerPermissionErrorPausesInsteadOfBecomingProfileFailure() {
    fault = "SECURITY";
    run("missing", "bad", "next");
    assertEquals("STORAGE_PERMISSION_LOST", SessionState.prefs(c).getString("blocked", ""));
    try (QueueStore q = new QueueStore(c)) {
      assertEquals("RETRY_LATER", status(q, "bad"));
      assertEquals("PENDING", status(q, "next"));
    }
  }
}
