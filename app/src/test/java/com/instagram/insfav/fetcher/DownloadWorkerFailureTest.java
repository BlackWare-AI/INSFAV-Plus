package com.instagram.insfav.fetcher;
import org.junit.Test;import java.util.concurrent.ExecutionException;import static org.junit.Assert.*;
public class DownloadWorkerFailureTest {
 @Test public void onlyForegroundRestrictionsAreSystemRestricted(){assertEquals("SYSTEM_RESTRICTED",DownloadWorker.failureCode(new ExecutionException(new IllegalStateException()),true));assertEquals("SYSTEM_RESTRICTED",DownloadWorker.failureCode(new SecurityException(),true));assertEquals("WORKER_PROCESSING_ERROR",DownloadWorker.failureCode(new IllegalStateException(),false));assertEquals("STORAGE_PERMISSION_LOST",DownloadWorker.failureCode(new SecurityException(),false));assertEquals("FOREGROUND_START_FAILED",DownloadWorker.failureCode(new ExecutionException(new java.io.IOException()),true));}
}
