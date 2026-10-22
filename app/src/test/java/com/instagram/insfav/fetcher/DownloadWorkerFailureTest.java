package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import java.util.concurrent.ExecutionException;
import org.junit.Test;

public class DownloadWorkerFailureTest {
  @Test
  public void onlyForegroundRestrictionsAreSystemRestricted() {
    assertEquals(
        "SYSTEM_RESTRICTED",
        DownloadWorker.failureCode(new ExecutionException(new IllegalStateException()), true));
    assertEquals("SYSTEM_RESTRICTED", DownloadWorker.failureCode(new SecurityException(), true));
    assertEquals(
        "WORKER_PROCESSING_ERROR", DownloadWorker.failureCode(new IllegalStateException(), false));
    assertEquals(
        "STORAGE_PERMISSION_LOST", DownloadWorker.failureCode(new SecurityException(), false));
    assertEquals(
        "FOREGROUND_START_FAILED",
        DownloadWorker.failureCode(new ExecutionException(new java.io.IOException()), true));
  }
}
