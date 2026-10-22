package com.instagram.insfav;

import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** SAF latency simulation; this does not establish an optimal pool size for a real provider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RescanConcurrencyTest {
  @Test
  public void compareOneTwoAndFourWorkersWithBoundedIoAndIdenticalResults() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    File root = Files.createTempDirectory("scan-pools").toFile();
    ProviderInfo info = new ProviderInfo();
    info.authority = "rescan.fixture";
    info.exported = true;
    info.grantUriPermissions = true;
    RescanBenchmarkTest.Provider provider =
        Robolectric.buildContentProvider(RescanBenchmarkTest.Provider.class).create(info).get();
    provider.openDelayMs = 2;
    byte[] encoded;
    Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      assertTrue(b.compress(Bitmap.CompressFormat.WEBP, 90, out));
      encoded = out.toByteArray();
    } finally {
      b.recycle();
    }
    File sources = new File(root, "sources");
    assertTrue(sources.mkdir());
    for (int n = 0; n < 1024; n++) {
      String name = "fixture_" + n + ".webp";
      File f = new File(sources, name);
      Files.write(f.toPath(), encoded);
      provider.files.put(name, f);
    }
    String fingerprint = RescanBenchmarkTest.fingerprint(provider.files);
    JSONArray results = new JSONArray();
    for (int workers : new int[] {1, 2, 4}) {
      File checkpointRoot = new File(root, "private-" + workers);
      assertTrue(checkpointRoot.mkdir());
      provider.opens.set(0);
      provider.queries.set(0);
      provider.peak.set(0);
      long start = System.nanoTime();
      try (ThumbnailScanner scanner = new ThumbnailScanner(context, checkpointRoot, workers)) {
        ThumbnailPolicy.Catalog catalog =
            scanner.scan(
                Uri.parse("content://rescan.fixture/tree/root"),
                "root",
                null,
                new CancellationSignal(),
                (p, t) -> {});
        assertEquals(1024, catalog.best.size());
        assertEquals(0, catalog.unreadable);
        scanner.index.complete();
        assertTrue(scanner.metrics().getInt("peakValidations") <= workers);
        assertEquals(1024, provider.opens.get());
        assertEquals(1, provider.queries.get());
        assertTrue(provider.peak.get() <= workers);
        results.put(
            new JSONObject()
                .put("workers", workers)
                .put("elapsedMs", (System.nanoTime() - start) / 1000000)
                .put("opens", provider.opens.get())
                .put("metadataQueries", provider.queries.get())
                .put("peakOpenCalls", provider.peak.get())
                .put("peakValidations", scanner.metrics().getInt("peakValidations")));
      }
    }
    assertEquals(fingerprint, RescanBenchmarkTest.fingerprint(provider.files));
    assertEquals(0, provider.writes.get());
    System.out.println(
        "RESCAN_CONCURRENCY "
            + new JSONObject()
                .put(
                    "environment",
                    "Robolectric API34, 1024 real synthetic WebP files, 2 ms simulated SAF open"
                        + " latency")
                .put("results", results));
  }
}
