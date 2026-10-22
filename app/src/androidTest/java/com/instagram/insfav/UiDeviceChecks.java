package com.instagram.insfav;

import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.WebView;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Real bridge → authoritative missing list → Android clipboard; synthetic app data only. */
public final class UiDeviceChecks {
  public static void run(Instrumentation instrumentation) throws Exception {
    Context c = instrumentation.getTargetContext();
    Store store = Store.get(c);
    String before = store.original();
    Activity activity = null;
    String expected = "fixture_one\nfixture_two\nfixture_three";
    try {
      store.importDocument(
          "[{\"name\":\"Folder"
              + " A\",\"items\":[{\"pageId\":\"fixture_one\"},{\"pageId\":\"fixture_two\"}]},{\"name\":\"Folder"
              + " B\",\"items\":[{\"pageId\":\"@FIXTURE_ONE\"},{\"pageId\":\"fixture_three\"}]}]");
      activity =
          instrumentation.startActivitySync(
              new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Field field = MainActivity.class.getDeclaredField("web");
      field.setAccessible(true);
      WebView web = (WebView) field.get(activity);
      java.util.concurrent.atomic.AtomicBoolean ready =
          new java.util.concurrent.atomic.AtomicBoolean();
      long deadline = SystemClock.elapsedRealtime() + 15000;
      while (!ready.get() && SystemClock.elapsedRealtime() < deadline) {
        instrumentation.runOnMainSync(
            () ->
                web.evaluateJavascript(
                    "!!window.request && document.getElementById('splash').hidden",
                    value -> ready.set("true".equals(value))));
        Thread.sleep(100);
      }
      if (!ready.get()) throw new AssertionError("App bridge not ready");
      instrumentation.runOnMainSync(
          () -> web.evaluateJavascript("request('copy-missing-users')", null));
      java.util.concurrent.atomic.AtomicReference<String> actual =
          new java.util.concurrent.atomic.AtomicReference<>("");
      deadline = SystemClock.elapsedRealtime() + 10000;
      while (!expected.equals(actual.get()) && SystemClock.elapsedRealtime() < deadline) {
        instrumentation.runOnMainSync(
            () -> {
              ClipData clip = c.getSystemService(ClipboardManager.class).getPrimaryClip();
              if (clip != null && clip.getItemCount() > 0)
                actual.set(clip.getItemAt(0).coerceToText(c).toString());
            });
        Thread.sleep(100);
      }
      if (!expected.equals(actual.get()))
        throw new AssertionError(
            "Clipboard must contain exactly three unique usernames and no folders");
      JSONObject proof =
          new JSONObject()
              .put("sdk", Build.VERSION.SDK_INT)
              .put("nativeBridgeClipboardVerified", true)
              .put("copiedUsernames", 3)
              .put("folderNamesExcluded", true)
              .put("duplicatesRemoved", true)
              .put("fixture", true);
      try (FileOutputStream out = c.openFileOutput("polish-ui.json", Context.MODE_PRIVATE)) {
        out.write(proof.toString(2).getBytes(StandardCharsets.UTF_8));
      }
      Bundle status = new Bundle();
      status.putString("stream", "POLISH_UI_OK " + proof + "\n");
      instrumentation.sendStatus(0, status);
    } finally {
      if (before.isEmpty()) store.clearDocument();
      else store.importDocument(before);
      if (activity != null) {
        Activity current = activity;
        instrumentation.runOnMainSync(current::finish);
      }
    }
  }

  private UiDeviceChecks() {}
}
