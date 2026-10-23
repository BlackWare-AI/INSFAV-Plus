package com.instagram.insfav;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import java.io.*;
import java.nio.file.Files;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Same Plus scanner, files, worker count and provider; only the SQLite batch varies. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RescanBatchBenchmarkTest {
  @Test
  public void compare256And512WithFifteenThousandSources() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    File root = Files.createTempDirectory("plus-batch-paired").toFile();
    ProviderInfo info = new ProviderInfo();
    info.authority = "rescan.batch.fixture";
    info.exported = true;
    info.grantUriPermissions = true;
    RescanBenchmarkTest.Provider provider =
        Robolectric.buildContentProvider(RescanBenchmarkTest.Provider.class).create(info).get();
    byte[][] bytes = new byte[2][];
    for (int i = 0; i < 2; i++) {
      Bitmap bitmap =
          Bitmap.createBitmap(i == 0 ? 128 : 256, i == 0 ? 128 : 256, Bitmap.Config.ARGB_8888);
      try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out));
        bytes[i] = out.toByteArray();
      } finally {
        bitmap.recycle();
      }
    }
    File source = new File(root, "source");
    assertTrue(source.mkdir());
    for (int i = 0; i < 15000; i++) {
      String name = "batch_" + i + ".webp";
      File f = new File(source, name);
      Files.write(f.toPath(), bytes[i % 2]);
      provider.files.put(name, f);
    }
    String fingerprint = RescanBenchmarkTest.fingerprint(provider.files);
    JSONArray samples = new JSONArray();
    Uri tree = Uri.parse("content://rescan.batch.fixture/tree/root");
    for (int repeat = 0; repeat < 3; repeat++)
      for (int batch : repeat % 2 == 0 ? new int[] {256, 512} : new int[] {512, 256}) {
        File checkpoint = new File(root, "checkpoint-" + repeat + "-" + batch);
        assertTrue(checkpoint.mkdir());
        int before = provider.opens.get();
        long began = System.nanoTime();
        JSONObject coldMetrics;
        long cold;
        try (ThumbnailScanner scanner = new ThumbnailScanner(context, checkpoint, 2, batch)) {
          assertEquals(
              15000,
              scanner.scan(tree, "root", null, new CancellationSignal(), (p, t) -> {}).files);
          scanner.index.complete();
          cold = System.nanoTime() - began;
          coldMetrics = scanner.metrics();
        }
        assertEquals(15000, provider.opens.get() - before);
        before = provider.opens.get();
        began = System.nanoTime();
        long warm;
        JSONObject warmMetrics;
        try (ThumbnailScanner scanner = new ThumbnailScanner(context, checkpoint, 2, batch)) {
          assertEquals(
              15000,
              scanner.scan(tree, "root", null, new CancellationSignal(), (p, t) -> {}).files);
          scanner.index.complete();
          warm = System.nanoTime() - began;
          warmMetrics = scanner.metrics();
        }
        assertEquals(before, provider.opens.get());
        assertEquals(15000, warmMetrics.getInt("reused"));
        assertTrue(coldMetrics.getInt("peakValidations") <= 2);
        samples.put(
            new JSONObject()
                .put("repeat", repeat)
                .put("batch", batch)
                .put("coldNanos", cold)
                .put("warmNanos", warm)
                .put("cold", coldMetrics)
                .put("warm", warmMetrics));
      }
    assertEquals(fingerprint, RescanBenchmarkTest.fingerprint(provider.files));
    assertEquals(0, provider.writes.get());
    System.out.println(
        "BATCH_COMPARISON "
            + new JSONObject()
                .put(
                    "environment",
                    "Same Plus scanner, host memory-backed filesystem/Robolectric API34, two"
                        + " workers, 15000 real synthetic 128/256 WebP, 1ms source-open delay; not"
                        + " a device speed claim")
                .put("samples", samples)
                .put("sourceWrites", 0)
                .put("sourceBytesAndDatesUnchanged", true));
  }
}
