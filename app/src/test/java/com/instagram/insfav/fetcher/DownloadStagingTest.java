package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.junit.Test;

public class DownloadStagingTest {
  private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(10); }
  private static byte[] hash(File file) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
  }

  @Test public void writesToScratchBeforeReturningAndRemovesOnlyAbandonedScratch() throws Exception {
    File directory = Files.createTempDirectory("download-staging").toFile();
    File keep = new File(directory, "existing.webp"), orphan = new File(directory, "insfav-source-abandoned.part");
    Files.write(keep.toPath(), new byte[] {4, 5, 6});
    Files.write(orphan.toPath(), new byte[] {1});
    byte[] before = hash(keep), data = new byte[16000];
    new java.util.Random(4).nextBytes(data);
    InputStream input = new ByteArrayInputStream(data) {
      int reads;
      @Override public synchronized int read(byte[] b, int off, int count) {
        File[] staged = directory.listFiles((d, n) -> n.startsWith("insfav-source-") && n.endsWith(".part"));
        assertEquals(1, staged.length);
        if (reads++ > 0) assertTrue(staged[0].length() > 0);
        return super.read(b, off, count);
      }
    };
    assertArrayEquals(data, DownloadStaging.read(input, directory, 20000, data.length, deadline(), () -> false));
    assertFalse(orphan.exists());
    assertArrayEquals(before, hash(keep));
    assertArrayEquals(new String[] {"existing.webp"}, directory.list());
  }

  @Test public void failuresAndCancellationCleanScratchWithoutChangingExistingSha256() throws Exception {
    File directory = Files.createTempDirectory("download-failures").toFile();
    File keep = new File(directory, "existing.webp");
    Files.write(keep.toPath(), new byte[] {4, 5, 6});
    byte[] before = hash(keep);
    for (int mode = 0; mode < 6; mode++) {
      final int failure = mode;
      InputStream input = mode == 3 ? new InputStream() {
        boolean started;
        @Override public int read() throws IOException {
          if (started) throw new IOException("connection lost");
          started = true; return 1;
        }
      } : new ByteArrayInputStream(mode == 0 ? new byte[0] : new byte[] {1, 2});
      try {
        DownloadStaging.read(input, directory, mode == 2 ? 1 : 10, mode == 1 ? 3 : -1,
            mode == 5 ? 0 : deadline(), () -> failure == 4);
        fail("Invalid transfer must fail: " + mode);
      } catch (IOException | ProfileProvider.Failure expected) { }
      assertArrayEquals(before, hash(keep));
      assertArrayEquals(new String[] {"existing.webp"}, directory.list());
    }
  }

  @Test public void anotherDownloadCannotCleanAnActiveScratchFile() throws Exception {
    File directory = Files.createTempDirectory("parallel-staging").toFile();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<byte[]> first = executor.submit(() -> DownloadStaging.read(new ByteArrayInputStream(new byte[] {1}) {
        @Override public synchronized int read(byte[] b, int off, int len) {
          entered.countDown();
          try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
          catch (InterruptedException error) { throw new AssertionError(error); }
          return super.read(b, off, len);
        }
      }, directory, 10, 1, deadline(), () -> false));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertArrayEquals(new byte[] {2}, DownloadStaging.read(new ByteArrayInputStream(new byte[] {2}), directory, 10, 1, deadline(), () -> false));
      assertEquals(1, directory.list().length);
      release.countDown();
      assertArrayEquals(new byte[] {1}, first.get(5, TimeUnit.SECONDS));
      assertEquals(0, directory.list().length);
    } finally { release.countDown(); executor.shutdownNow(); }
  }
}
