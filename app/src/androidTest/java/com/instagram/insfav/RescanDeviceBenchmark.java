package com.instagram.insfav;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Actual Android SAF/Binder and SQLite on 15,000 synthetic files, never live Instagram. */
public final class RescanDeviceBenchmark {
  public static void run(Instrumentation instrumentation) throws Exception {
    Context target = instrumentation.getTargetContext();
    target.sendBroadcast(
        new Intent()
            .setComponent(
                new ComponentName(
                    "com.instagram.insfav.plus.test", RescanDocuments.Grant.class.getName())));
    boolean granted = false;
    long deadline = SystemClock.elapsedRealtime() + 120000;
    while (!granted && SystemClock.elapsedRealtime() < deadline)
      try {
        target
            .getContentResolver()
            .takePersistableUriPermission(
                RescanDocuments.tree(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        granted = true;
      } catch (SecurityException retry) {
        Thread.sleep(100);
      }
    require(granted, "Missing synthetic read-only SAF grant");
    File privateRoot = new File(target.getCacheDir(), "rescan-benchmark-private");
    require(privateRoot.mkdirs() || privateRoot.isDirectory(), "Private fixture directory");
    Context isolated =
        new ContextWrapper(target) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public File getFilesDir() {
            return privateRoot;
          }
        };
    Store store = new Store(isolated);
    JSONArray items = new JSONArray();
    for (int n = 0; n < 15000; n++) items.put(new JSONObject().put("pageId", "fixture_" + n));
    String original =
        new JSONArray()
            .put(new JSONObject().put("name", "Offline rescan fixture").put("items", items))
            .toString();
    store.importDocument(original);
    LocalImages images = new LocalImages(isolated, store);
    images.select(RescanDocuments.tree(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
    images.clearIndex();
    Bundle before = stats(target);
    require(before.getInt("files") == 15000, "Fixture file count");
    android.os.Debug.MemoryInfo memory = new android.os.Debug.MemoryInfo();
    android.os.Debug.getMemoryInfo(memory);
    int beforePss = memory.getTotalPss();
    long started = SystemClock.elapsedRealtime();
    images.scan((e, v) -> {}, false);
    long cold = SystemClock.elapsedRealtime() - started;
    require("completed".equals(store.settings.optString("scanState")), "Cold scan failed");
    require(images.snapshot(false).getInt("matched") == 15000, "Cold matched count");
    JSONObject audit = images.snapshot(false).getJSONObject("resolution");
    require(
        audit.getBoolean("complete") && audit.getInt("count") == 7500,
        "Strict 256 target must include every 128 image");
    Bundle afterCold = stats(target);
    long revision = store.index.optLong("revision");
    JSONObject coldIo = images.snapshot(false).getJSONObject("scan").getJSONObject("io");
    started = SystemClock.elapsedRealtime();
    images.scan((e, v) -> {}, false);
    long warm = SystemClock.elapsedRealtime() - started;
    require("completed".equals(store.settings.optString("scanState")), "Warm scan failed");
    require(revision == store.index.optLong("revision"), "Unchanged revision");
    Bundle afterWarm = stats(target);
    require(afterWarm.getInt("opens") == afterCold.getInt("opens"), "Warm source was opened");
    require(
        afterCold.getInt("opens") - before.getInt("opens") == 15000, "Unexpected cold open count");
    require(
        before.getString("fingerprint").equals(afterWarm.getString("fingerprint")),
        "Source bytes or timestamps changed");
    require(afterWarm.getInt("writes") == 0, "Source write attempted");
    require(original.equals(store.original()), "Original collection changed");
    Store reopened = new Store(isolated);
    LocalImages restarted = new LocalImages(isolated, reopened);
    restarted.checkAccess();
    require(restarted.snapshot(false).getInt("matched") == 15000, "Reopened index count");
    Bundle afterRestart = stats(target);
    require(
        afterRestart.getInt("opens") == afterWarm.getInt("opens"), "Restart read source images");
    require(
        afterRestart.getInt("queries") - afterWarm.getInt("queries") == 1,
        "Restart relisted folder");
    android.os.Debug.getMemoryInfo(memory);
    JSONObject report =
        new JSONObject()
            .put(
                "environment",
                "Android emulator, real Binder SAF provider; 15000 synthetic 128/256 WebP files, no"
                    + " artificial delay")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("coldMs", cold)
            .put("warmMs", warm)
            .put("coldOpens", afterCold.getInt("opens") - before.getInt("opens"))
            .put("warmOpens", 0)
            .put("coldQueries", afterCold.getInt("queries") - before.getInt("queries"))
            .put("warmQueries", afterWarm.getInt("queries") - afterCold.getInt("queries"))
            .put("pssBeforeKiB", beforePss)
            .put("pssAfterKiB", memory.getTotalPss())
            .put("coldIo", coldIo)
            .put("warmIo", images.snapshot(false).getJSONObject("scan").getJSONObject("io"))
            .put("non256Images", audit.getInt("count"))
            .put("sourceBytesAndDatesUnchanged", true)
            .put("sourceWrites", 0)
            .put("restartRelistedFolder", false)
            .put("liveInstagramTested", false)
            .put("physicalDeviceTested", false);
    try (FileOutputStream out = target.openFileOutput("rescan-15000.json", Context.MODE_PRIVATE)) {
      out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    Bundle status = new Bundle();
    status.putString("stream", "RESCAN_DEVICE_OK " + report + "\n");
    instrumentation.sendStatus(0, status);
  }

  private static Bundle stats(Context c) {
    return c.getContentResolver().call(RescanDocuments.tree(), "fixture.stats", null, null);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private RescanDeviceBenchmark() {}
}
