package com.instagram.insfav;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Same metadata workload on the original Store and Plus. No image download is simulated as live.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class IndexWriteBenchmarkTest {
  @Test
  public void measureSixtyFourUpdatesWithFifteenThousandIndexedProfiles() throws Exception {
    Store store = new Store(RuntimeEnvironment.getApplication());
    String tree = "content://benchmark/tree/root";
    store.settings.put("treeUri", tree).put("directoryId", "root");
    store.saveSettings();
    JSONObject profiles = new JSONObject();
    for (int i = 0; i < 15000; i++) profiles.put("account" + i, profile("account" + i));
    store.replaceIndex(
        new JSONObject()
            .put("schema", 1)
            .put("treeUri", tree)
            .put("revision", 1)
            .put("files", 15000)
            .put("bytes", 15000L * 256)
            .put("profiles", profiles));
    Method incremental = null, flush = null, metrics = null;
    try {
      incremental =
          Store.class.getDeclaredMethod(
              "updateProfile", String.class, JSONObject.class, String.class);
      flush = Store.class.getDeclaredMethod("flushIndex");
      metrics = Store.class.getDeclaredMethod("writeMetrics");
    } catch (NoSuchMethodException original) {
    }
    JSONObject initial = metrics == null ? new JSONObject() : (JSONObject) metrics.invoke(store);
    JSONObject ioBefore = processIo();
    long started = System.nanoTime(), written = 0;
    int snapshots = 0;
    for (int i = 0; i < 64; i++) {
      String user = "added" + i;
      JSONObject p = profile(user);
      if (incremental != null) incremental.invoke(store, user, p, tree);
      else {
        profiles.put(user, p);
        store.index.put("revision", 2 + i).put("files", 15001 + i).put("bytes", (15001L + i) * 256);
        store.replaceIndex(store.index);
        snapshots++;
        written += new File(store.root, "thumbnail-index.json").length();
      }
    }
    if (flush != null) flush.invoke(store);
    long elapsed = System.nanoTime() - started;
    JSONObject ioAfter = processIo();
    for (String key : new String[] {"wchar", "syscw", "write_bytes"})
      if (ioAfter.has(key) && ioBefore.has(key))
        ioAfter.put(key, ioAfter.getLong(key) - ioBefore.getLong(key));
    assertEquals(15064, store.index.getJSONObject("profiles").length());
    assertEquals(15064, store.index.getInt("files"));
    JSONObject measured =
        metrics == null
            ? new JSONObject()
                .put("snapshotWrites", snapshots)
                .put("snapshotSerializedBytes", written)
            : (JSONObject) metrics.invoke(store);
    if (metrics != null)
      for (String key :
          new String[] {
            "snapshotWrites", "snapshotSerializedBytes", "snapshotNanos", "deltaWrites"
          }) measured.put(key, measured.optLong(key) - initial.optLong(key));
    System.out.println(
        "INDEX_WRITE "
            + new JSONObject()
                .put("environment", "Host filesystem + Robolectric; metadata only")
                .put("initialProfiles", 15000)
                .put("updates", 64)
                .put("elapsedNanos", elapsed)
                .put("incremental", incremental != null)
                .put("writes", measured)
                .put("linuxProcessIoDelta", ioAfter));
  }

  private JSONObject processIo() {
    JSONObject out = new JSONObject();
    try {
      for (String line :
          new String(
                  java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/self/io")),
                  java.nio.charset.StandardCharsets.UTF_8)
              .split("\n")) {
        String[] part = line.split(":\s*");
        if (java.util.Arrays.asList("wchar", "syscw", "write_bytes").contains(part[0]))
          out.put(part[0], Long.parseLong(part[1]));
      }
    } catch (Exception ignored) {
    }
    return out;
  }

  private JSONObject profile(String user) throws Exception {
    return new JSONObject()
        .put("uri", "content://benchmark/document/" + user)
        .put("name", user + ".webp")
        .put("size", 256)
        .put("width", 256)
        .put("height", 256)
        .put("modified", 1)
        .put("readable", true);
  }
}
