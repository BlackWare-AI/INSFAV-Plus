package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.graphics.*;
import android.net.Uri;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FetcherCatalogTest {
  Context c;
  FetcherStorageTest.Fixture provider;
  Uri tree = Uri.parse("content://fetcher.test/tree/root");
  File folder;

  @Before
  public void setup() throws Exception {
    c = RuntimeEnvironment.getApplication();
    c.deleteDatabase("fetcher-queue.db");
    QueueControl.requested = false;
    QueueControl.lock.set(false);
    SessionState.prefs(c)
        .edit()
        .clear()
        .putString("tree", tree.toString())
        .putString("directoryId", "root/.thumbnails")
        .commit();
    ProviderInfo info = new ProviderInfo();
    info.authority = "fetcher.test";
    info.exported = true;
    info.grantUriPermissions = true;
    info.readPermission = "android.permission.MANAGE_DOCUMENTS";
    info.writePermission = info.readPermission;
    provider =
        Robolectric.buildContentProvider(FetcherStorageTest.Fixture.class).create(info).get();
    provider.root = Files.createTempDirectory("fetcher-catalog").toFile();
    folder = new File(provider.root, ".thumbnails");
    folder.mkdir();
    c.getContentResolver()
        .takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    FetcherCatalog.listings.set(0);
    FetcherCatalog.opens.set(0);
    FetcherCatalog.decodes.set(0);
    FetcherCatalog.hits.set(0);
    Fetcher.initialize(
        c,
        new Fetcher.Host() {
          public Collection<String> users() {
            return Collections.emptyList();
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

          public boolean hasImage(String user) {
            throw new AssertionError("No legacy validation");
          }

          public void imageWritten(String u, Uri uri, String t) {}
        });
  }

  byte[] webp(int size) throws Exception {
    Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    b.compress(Bitmap.CompressFormat.WEBP, 78, o);
    b.recycle();
    return o.toByteArray();
  }

  @Test(timeout = 240000)
  public void
      thirteenThousandFiveHundredFilesAreIndexedOnceAndWarmStartResumeNeedNoListingOpenOrDecode()
          throws Exception {
    byte[] bytes = webp(128);
    List<String> users = new ArrayList<>();
    for (int i = 0; i < 13500; i++) {
      String user = "synthetic_" + i;
      users.add(user);
      Files.write(new File(folder, user + ".webp").toPath(), bytes);
    }
    long first;
    try (QueueStore q = new QueueStore(c)) {
      q.sync(users);
      long at = System.nanoTime();
      new OutputFolder(c, tree.toString(), q, false, () -> {});
      first = (System.nanoTime() - at) / 1000000;
      assertEquals(Integer.valueOf(13500), q.counts().get("EXISTING"));
      assertEquals(1, FetcherCatalog.listings.get());
      assertEquals(27000, FetcherCatalog.opens.get());
      assertEquals(27000, FetcherCatalog.decodes.get());
    }
    int queries = provider.childQueries, opens = provider.opens;
    long warm;
    try (QueueStore q = new QueueStore(c)) {
      long at = System.nanoTime();
      q.sync(users);
      q.prepareMissing();
      q.recoverInterrupted();
      new OutputFolder(c, tree.toString(), q, false, () -> {});
      warm = (System.nanoTime() - at) / 1000000;
      assertTrue(q.eligible().isEmpty());
      assertEquals(13500, q.completedImages().size());
    }
    for (int restart = 0; restart < 2; restart++)
      try (QueueStore q = new QueueStore(c)) {
        q.recoverInterrupted();
        new OutputFolder(c, tree.toString(), q, false, () -> {});
        assertTrue(q.pending().isEmpty());
      }
    assertEquals(queries, provider.childQueries);
    assertEquals(opens, provider.opens);
    assertEquals(0, provider.writes);
    assertArrayEquals(bytes, Files.readAllBytes(new File(folder, "synthetic_0.webp").toPath()));
    System.out.println(
        "FETCHER_13500 first_ms="
            + first
            + " warm_ms="
            + warm
            + " first_listings=1 first_opens=27000 first_decodes=27000 warm_listings=0 warm_opens=0"
            + " warm_decodes=0");
  }

  @Test
  public void seedFromNormalMetadataNeedsNoSafReadsAndIsNotRepeatedAfterReopen() throws Exception {
    org.json.JSONObject p =
        new JSONObject()
            .put("uri", tree + "/document/root%2F.thumbnails%2Fone.webp")
            .put("name", "one.webp")
            .put("size", 100)
            .put("width", 256)
            .put("height", 256)
            .put("readable", true);
    java.util.concurrent.atomic.AtomicInteger snapshots =
        new java.util.concurrent.atomic.AtomicInteger();
    Fetcher.initialize(
        c,
        new Fetcher.Host() {
          public Collection<String> users() {
            return Arrays.asList("one");
          }

          public String tree() {
            return tree.toString();
          }

          public String directory() {
            return "root/.thumbnails";
          }

          public long revision() {
            return 10;
          }

          public JSONObject catalog() throws Exception {
            snapshots.incrementAndGet();
            return new JSONObject()
                .put("treeUri", tree.toString())
                .put("profiles", new JSONObject().put("one", p));
          }

          public boolean hasImage(String u) {
            throw new AssertionError();
          }

          public void imageWritten(String u, Uri uri, String t) {}
        });
    for (int run = 0; run < 3; run++)
      try (QueueStore q = new QueueStore(c)) {
        q.sync(Arrays.asList("one"));
        assertTrue(FetcherCatalog.seed(q));
        assertEquals(Integer.valueOf(1), q.counts().get("EXISTING"));
      }
    assertEquals(1, snapshots.get());
    assertEquals(0, provider.opens);
    assertEquals(0, provider.childQueries);
  }

  @Test
  public void resolutionIsPersistentUnreadableIsSeparateAndNoFileIsDeleted() throws Exception {
    Files.write(new File(folder, "valid.webp").toPath(), webp(256));
    byte[] invalid = webp(64);
    Files.write(new File(folder, "wrong.webp").toPath(), invalid);
    Files.write(new File(folder, "broken.webp").toPath(), new byte[] {1, 2, 3});
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("valid", "wrong", "broken"));
      new OutputFolder(c, tree.toString(), q, false, () -> {});
      assertEquals(1, FetcherCatalog.resolution(q, 0).getInt("count"));
      assertEquals(
          64,
          FetcherCatalog.resolution(q, 0).getJSONArray("items").getJSONObject(0).getInt("width"));
      assertFalse(q.image("broken").readable);
      assertTrue(new OutputFolder(c, tree.toString(), q, false, () -> {}).valid("wrong.webp"));
    }
    int opens = provider.opens;
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(1, FetcherCatalog.resolution(q, -1).getInt("count"));
    }
    assertEquals(opens, provider.opens);
    assertArrayEquals(invalid, Files.readAllBytes(new File(folder, "wrong.webp").toPath()));
  }

  @Test
  public void
      interruptedClaimsRecoverOnlyUncertainRowsAndExplicitVerificationDetectsExternalChanges()
          throws Exception {
    Files.write(new File(folder, "one.webp").toPath(), webp(128));
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("one", "two"));
      new OutputFolder(c, tree.toString(), q, false, () -> {});
      assertTrue(q.claim("two"));
      q.mark("two", "RETRY_LATER", "RATE_LIMITED");
      assertEquals(Integer.valueOf(1), q.phases().get("RATE_LIMITED"));
      assertEquals(0, q.attempts("two"));
    }
    assertTrue(new File(folder, "one.webp").delete());
    Files.write(new File(folder, "two.webp").toPath(), webp(256));
    try (QueueStore q = new QueueStore(c)) {
      q.recoverInterrupted();
      new OutputFolder(c, tree.toString(), q, true, () -> {});
      assertTrue(q.pending().contains("one"));
      assertEquals("COMPLETED", phase(q, "two"));
      assertFalse(q.image("one").readable);
      assertTrue(q.image("two").readable);
    }
  }

  @Test
  public void exactDocumentChangeInvalidatesOnlyThatRecordWithoutFolderEnumeration()
      throws Exception {
    Files.write(new File(folder, "one.webp").toPath(), webp(128));
    Files.write(new File(folder, "two.webp").toPath(), webp(256));
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("one", "two"));
      new OutputFolder(c, tree.toString(), q, false, () -> {});
      String uri = q.image("one").uri;
      int before = provider.childQueries;
      assertTrue(new File(folder, "one.webp").delete());
      q.invalidateUri(uri);
      assertTrue(q.pending().contains("one"));
      assertFalse(new OutputFolder(c, tree.toString(), q, false, () -> {}).valid("one.webp"));
      assertTrue(q.image("two").readable);
      assertEquals(before, provider.childQueries);
    }
  }

  @Test
  public void failedAndSkippedDoNotLoopUntilExplicitRetryAndClaimsAreExclusive() throws Exception {
    try (QueueStore q = new QueueStore(c)) {
      q.sync(Arrays.asList("one", "two"));
      assertTrue(q.claim("one"));
      assertFalse(q.claim("one"));
      q.mark("one", "FAILED", "NETWORK_ERROR");
      q.mark("two", "SKIPPED_UNAVAILABLE", "PROFILE_NOT_FOUND_OR_UNAVAILABLE");
      assertTrue(q.eligible().isEmpty());
      q.retrySkipped();
      assertEquals(2, q.eligible().size());
    }
  }

  @Test
  public void migrationPreservesCompletedSkippedFailedAndAttemptCounts() throws Exception {
    c.deleteDatabase("fetcher-queue.db");
    android.database.sqlite.SQLiteDatabase old =
        c.openOrCreateDatabase("fetcher-queue.db", 0, null);
    old.execSQL(
        "CREATE TABLE accounts (username TEXT PRIMARY KEY,position INTEGER NOT NULL,status TEXT NOT"
            + " NULL,code TEXT NOT NULL DEFAULT '',eligible INTEGER NOT NULL DEFAULT 1,attempts"
            + " INTEGER NOT NULL DEFAULT 0)");
    old.execSQL(
        "INSERT INTO accounts VALUES"
            + " ('one',0,'SUCCESS','VALID_WEBP',1,0),('two',1,'FAILED','NETWORK_ERROR',1,2),('three',2,'SKIPPED_UNAVAILABLE','NOT_FOUND',1,0)");
    old.setVersion(3);
    old.close();
    try (QueueStore q = new QueueStore(c)) {
      assertEquals(Integer.valueOf(1), q.counts().get("SUCCESS"));
      assertEquals(Integer.valueOf(1), q.counts().get("FAILED"));
      assertEquals(2, q.attempts("two"));
      assertEquals("COMPLETED", phase(q, "one"));
      assertEquals("SKIPPED", phase(q, "three"));
      assertTrue(q.eligible().isEmpty());
    }
  }

  String phase(QueueStore q, String u) {
    try (android.database.Cursor c =
        q.getReadableDatabase()
            .rawQuery("SELECT phase FROM accounts WHERE username=?", new String[] {u})) {
      c.moveToFirst();
      return c.getString(0);
    }
  }
}
