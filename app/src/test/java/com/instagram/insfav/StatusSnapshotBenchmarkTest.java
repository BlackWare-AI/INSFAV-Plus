package com.instagram.insfav;

import static org.junit.Assert.*;

import android.content.*;
import android.net.Uri;
import java.lang.reflect.Method;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StatusSnapshotBenchmarkTest {
  @Test
  public void measureUnchangedResumeAndOneProfileResponseForFifteenThousandAccounts()
      throws Exception {
    LocalImagesTest harness = new LocalImagesTest();
    harness.setup();
    Store store = ReflectionHelpers.getField(harness, "store");
    LocalImages images = ReflectionHelpers.getField(harness, "images");
    String tree = "content://insfav.test/tree/root";
    images.select(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));
    JSONArray items = new JSONArray();
    JSONObject profiles = new JSONObject();
    for (int i = 0; i < 15000; i++) {
      String user = "fixture_" + i;
      items.put(new JSONObject().put("pageId", user));
      profiles.put(
          user,
          new JSONObject()
              .put("uri", "content://insfav.test/document/root%2FExample.JPG")
              .put("name", user + ".webp")
              .put("readable", true)
              .put("width", 256)
              .put("height", 256)
              .put("size", 256)
              .put("modified", 1));
    }
    store.importDocument(
        new JSONArray()
            .put(new JSONObject().put("name", "Synthetic").put("items", items))
            .toString());
    store.replaceIndex(
        new JSONObject()
            .put("schema", 1)
            .put("treeUri", tree)
            .put("revision", 1)
            .put("profiles", profiles));
    Method changes = null;
    try {
      changes =
          LocalImages.class.getDeclaredMethod(
              "snapshotChanges", long.class, String.class, boolean.class);
    } catch (NoSuchMethodException old) {
    }
    JSONObject first =
        changes == null ? images.snapshot(true) : (JSONObject) changes.invoke(images, 0L, "", true);
    String scope = first.optString("scopeRevision");
    long revision = first.getLong("revision"), serialized = 0, started = System.nanoTime();
    int responses = 20;
    for (int i = 0; i < responses; i++) {
      JSONObject response =
          changes == null
              ? images.snapshot(true)
              : (JSONObject) changes.invoke(images, revision, scope, false);
      assertEquals(15000, response.getInt("matched"));
      serialized += response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
    long elapsed = System.nanoTime() - started;
    JSONObject changed = profiles.getJSONObject("fixture_1");
    changed.put("modified", 2);
    try {
      Store.class
          .getDeclaredMethod("updateProfile", String.class, JSONObject.class, String.class)
          .invoke(store, "fixture_1", changed, tree);
    } catch (NoSuchMethodException old) {
      store.index.put("revision", 2);
      store.replaceIndex(store.index);
    }
    JSONObject patch =
        changes == null
            ? images.snapshot(true)
            : (JSONObject) changes.invoke(images, revision, scope, false);
    assertTrue(patch.has("profiles") || patch.has("profileChanges"));
    System.out.println(
        "STATUS_SNAPSHOT "
            + new JSONObject()
                .put(
                    "environment",
                    "Host Robolectric API34; metadata only, current onResume contract")
                .put("profiles", 15000)
                .put("unchangedResponses", responses)
                .put("unchangedResponseBytes", serialized)
                .put("elapsedNanos", elapsed)
                .put(
                    "singleProfileResponseBytes",
                    patch.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .put("deltaContract", changes != null));
  }
}
