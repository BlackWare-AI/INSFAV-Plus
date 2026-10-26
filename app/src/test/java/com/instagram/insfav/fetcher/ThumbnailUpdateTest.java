package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Color;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ThumbnailUpdateTest {
  FetcherStorageTest harness;
  Context context;
  File folder, target, other;
  byte[] old, next, before, otherBefore;

  @Before public void setup() throws Exception {
    harness = new FetcherStorageTest(); harness.setup(); context = harness.context;
    SessionState.prefs(context).edit().putString("directoryId", "root/.thumbnails").commit();
    folder = new File(harness.provider.root, ".thumbnails"); assertTrue(folder.mkdir());
    target = new File(folder, "example.webp"); other = new File(folder, "other.webp");
    old = ThumbnailCodec.convert(CodecTest.png(400, 400));
    next = ThumbnailCodec.convert(CodecTest.png(640, 480, Color.BLUE));
    assertFalse(Arrays.equals(old, next));
    Files.write(target.toPath(), old); Files.write(other.toPath(), old);
    assertTrue(target.setLastModified(123450000L));
    before = sha(target); otherBefore = sha(other);
  }

  static byte[] sha(File file) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
  }
  OutputFolder output() throws Exception { return new OutputFolder(context, harness.tree.toString()); }
  void unchanged() throws Exception {
    assertTrue(target.exists()); assertArrayEquals(before, sha(target));
    assertArrayEquals(otherBefore, sha(other)); assertEquals(123450000L, target.lastModified());
  }

  @Test public void validatedUpdateChangesOnlyItsTargetAndIdenticalContentIsNotRewritten() throws Exception {
    OutputFolder output = output(); output.commit("example.webp", next, true);
    assertTrue(output.lastWriteCreated);
    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(next), sha(target));
    assertFalse(Arrays.equals(before, sha(target))); assertArrayEquals(otherBefore, sha(other));
    int writes = harness.provider.writes;
    output.commit("example.webp", next, true);
    assertFalse(output.lastWriteCreated); assertEquals(writes, harness.provider.writes);
    assertEquals(2, folder.list().length);
  }

  @Test public void smallSourceCannotReplaceOldEvenWhenConvertedTo256Webp() throws Exception {
    OutputFolder output = output();
    for (int[] size : new int[][] {{24,24}, {128,512}, {255,1024}}) {
      byte[] enlarged = ThumbnailCodec.convert(CodecTest.png(size[0], size[1], Color.BLUE));
      assertTrue(ThumbnailCodec.valid(enlarged));
      assertNull(HdThumbnailWriter.encode(CodecTest.png(size[0], size[1], Color.BLUE)));
      output.commit("example.webp", enlarged, false); unchanged();
      assertFalse(output.lastWriteCreated);
    }
    assertEquals(0, harness.provider.writes); assertEquals(2, folder.list().length);
  }

  @Test public void brokenEmptyHtmlAndTruncatedCandidatesLeaveBothSha256ValuesIntact() throws Exception {
    byte[] png = CodecTest.png(640, 480, Color.BLUE);
    for (byte[] candidate : new byte[][] {new byte[0], "<html>error</html>".getBytes("UTF-8"),
        new byte[] {1,2,3}, Arrays.copyOf(png, png.length / 2)}) {
      assertThrows(IOException.class, () -> output().commit("example.webp", ThumbnailCodec.convert(candidate), true));
      unchanged();
    }
    assertEquals(0, harness.provider.writes);
  }

  @Test public void renameFailureRollsBackAndPreservesOldSha256() throws Exception {
    harness.provider.rejectFinalRename = true;
    assertThrows(IOException.class, () -> output().commit("example.webp", next, true));
    unchanged(); harness.provider.rejectFinalRename = false; output(); unchanged();
    assertEquals(2, folder.list().length);
  }

  @Test public void failedFinalVerificationRollsBackAndPreservesOldSha256() throws Exception {
    harness.provider.corruptFinal = true;
    assertThrows(IOException.class, () -> output().commit("example.webp", next, true));
    unchanged(); harness.provider.corruptFinal = false; output(); unchanged();
    assertEquals(2, folder.list().length);
  }

  @Test public void crashAfterBackupRenameRestoresExactOldBytesOnReopen() throws Exception {
    harness.provider.crashAfterBackup = true;
    assertThrows(AssertionError.class, () -> output().commit("example.webp", next, true));
    File[] backups = folder.listFiles((d,n) -> n.endsWith(".bak"));
    assertEquals(1, backups.length); assertArrayEquals(before, sha(backups[0]));
    harness.provider.crashAfterBackup = false; output(); unchanged();
    assertEquals(2, folder.list().length);
  }

  @Test public void crashAfterFinalRenameKeepsNewOnlyAfterRecoveryVerification() throws Exception {
    harness.provider.crashAfterFinal = true;
    assertThrows(AssertionError.class, () -> output().commit("example.webp", next, true));
    File[] backups = folder.listFiles((d,n) -> n.endsWith(".bak"));
    assertEquals(1, backups.length); assertArrayEquals(before, sha(backups[0]));
    harness.provider.crashAfterFinal = false; output();
    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(next), sha(target));
    assertArrayEquals(otherBefore, sha(other)); assertEquals(2, folder.list().length);
  }

  @Test public void backupCleanupFailureRetainsJournalForSafeRetry() throws Exception {
    harness.provider.rejectBackupDelete = true;
    output().commit("example.webp", next, true);
    File journal = new File(context.getFilesDir(), "fetcher-write-journal.json");
    assertTrue(journal.exists()); byte[] verified = sha(target);
    harness.provider.rejectBackupDelete = false; output();
    assertFalse(journal.exists()); assertArrayEquals(verified, sha(target));
    assertArrayEquals(otherBefore, sha(other)); assertEquals(2, folder.list().length);
  }

  @Test public void retryAndCatalogReindexPreserveBothSha256Values() throws Exception {
    try (QueueStore queue = new QueueStore(context)) {
      queue.sync(Arrays.asList("example", "other"));
      queue.mark("example", "FAILED", "WEBVIEW_TIMEOUT"); queue.retrySkipped();
      new OutputFolder(context, harness.tree.toString(), queue, true, () -> {});
      unchanged(); assertTrue(queue.image("example").readable);
      queue.recoverInterrupted(); queue.prepareMissing();
      new OutputFolder(context, harness.tree.toString(), queue, true, () -> {});
      unchanged();
    }
    assertEquals(0, harness.provider.writes);
  }

  @Test public void noncanonicalFilenameIsPreservedDuringUpdate() throws Exception {
    File original = new File(folder, "@EXAMPLE.WebP"); assertTrue(target.renameTo(original));
    assertThrows(IOException.class, () -> output().commit("example.webp", next, true));
    assertArrayEquals(before, sha(original)); assertFalse(target.exists());
    assertArrayEquals(otherBefore, sha(other)); assertEquals(2, folder.list().length);
  }

  @Test public void unfinishedWriteInAnotherFolderCannotBeOverwritten() throws Exception {
    harness.provider.crashAfterBackup = true;
    assertThrows(AssertionError.class, () -> output().commit("example.webp", next, true));
    File journal = new File(context.getFilesDir(), "fetcher-write-journal.json");
    byte[] journalHash = sha(journal);
    File otherFolder = new File(harness.provider.root, "different"); assertTrue(otherFolder.mkdir());
    SessionState.prefs(context).edit().putString("directoryId", "root/different").commit();
    assertThrows(IOException.class, () -> output().commit("missing.webp", next, true));
    assertArrayEquals(journalHash, sha(journal)); assertEquals(0, otherFolder.list().length);
    harness.provider.crashAfterBackup = false;
    SessionState.prefs(context).edit().putString("directoryId", "root/.thumbnails").commit();
    output(); unchanged(); assertEquals(2, folder.list().length);
  }

  @Test public void recoveryRejectsOversizeFinalAndRestoresOriginalSha256() throws Exception {
    harness.provider.crashAfterFinal = true;
    assertThrows(AssertionError.class, () -> output().commit("example.webp", next, true));
    Files.write(target.toPath(), new byte[ThumbnailCodec.MAX_OUTPUT + 1]);
    harness.provider.crashAfterFinal = false; output(); unchanged();
    assertEquals(2, folder.list().length);
  }

  @Test public void failedRollbackRetainsOldBackupUntilNextRecovery() throws Exception {
    harness.provider.rejectFinalRename = true; harness.provider.rejectRestore = true;
    assertThrows(IOException.class, () -> output().commit("example.webp", next, true));
    File[] backups = folder.listFiles((d,n) -> n.endsWith(".bak"));
    assertEquals(1, backups.length); assertArrayEquals(before, sha(backups[0]));
    harness.provider.rejectFinalRename = false; harness.provider.rejectRestore = false;
    output(); unchanged(); assertEquals(2, folder.list().length);
  }
}
