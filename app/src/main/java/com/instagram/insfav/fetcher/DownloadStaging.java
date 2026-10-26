package com.instagram.insfav.fetcher;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;

/** Private download scratch files; never points at the thumbnail archive. */
final class DownloadStaging {
  private static final Object LOCK = new Object();
  private static final String PREFIX = "insfav-source-";

  static byte[] read(InputStream input, File directory, int limit, long expected,
      long deadline, BooleanSupplier cancelled) throws IOException, ProfileProvider.Failure {
    File file;
    FileOutputStream output;
    FileLock lease;
    synchronized (LOCK) {
      if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("DOWNLOAD_FAILED");
      clean(directory);
      file = File.createTempFile(PREFIX, ".part", directory);
      output = new FileOutputStream(file);
      try {
        lease = output.getChannel().lock();
      } catch (IOException | RuntimeException error) {
        output.close();
        file.delete();
        throw error;
      }
    }
    try (FileOutputStream owned = output; FileLock held = lease) {
      byte[] buffer = new byte[8192];
      long count = 0;
      int n;
      while ((n = input.read(buffer)) != -1) {
        check(deadline, cancelled);
        if (count + n > limit) throw new ProfileProvider.Failure("RESPONSE_TOO_LARGE");
        owned.write(buffer, 0, n);
        count += n;
      }
      check(deadline, cancelled);
      if (count == 0 || expected >= 0 && count != expected)
        throw new ProfileProvider.Failure("DOWNLOAD_FAILED");
      owned.getFD().sync();
      return Files.readAllBytes(file.toPath());
    } finally {
      synchronized (LOCK) {
        file.delete(); // An unsuccessful cleanup is retried on the next image download.
      }
    }
  }

  private static void check(long deadline, BooleanSupplier cancelled) throws ProfileProvider.Failure {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new ProfileProvider.Failure("REQUEST_CANCELLED");
    if (System.nanoTime() > deadline) throw new ProfileProvider.Failure("REQUEST_TIMEOUT");
  }

  private static void clean(File directory) {
    File[] files = directory.listFiles((dir, name) -> name.startsWith(PREFIX) && name.endsWith(".part"));
    if (files == null) return;
    for (File file : files) {
      try (RandomAccessFile old = new RandomAccessFile(file, "rw");
          FileLock lease = old.getChannel().tryLock()) {
        if (lease != null) file.delete();
      } catch (IOException | OverlappingFileLockException ignored) {
        // An active download owns its lease; only an abandoned scratch file may be removed.
      }
    }
  }

  private DownloadStaging() {}
}
