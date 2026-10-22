package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class ResultFilterTest {
  @Test
  public void eachOutcomeFilterIsExplicitAndKeepsQueueOrder() throws Exception {
    android.content.Context c = RuntimeEnvironment.getApplication();
    Fetcher.initialize(
        c,
        new Fetcher.Host() {
          public Collection<String> users() {
            return Arrays.asList("first", "second", "third");
          }

          public String tree() {
            return "";
          }

          public String directory() {
            return "";
          }

          public long revision() {
            return 0;
          }

          public boolean hasImage(String u) {
            return false;
          }

          public void imageWritten(String u, android.net.Uri uri, String tree) {}
        });
    for (String status :
        Arrays.asList(
            "SUCCESS",
            "EXISTING",
            "SKIPPED_UNAVAILABLE",
            "SKIPPED_NO_IMAGE",
            "FAILED",
            "RETRY_LATER")) {
      try (QueueStore queue = new QueueStore(c)) {
        queue.sync(Arrays.asList("first", "second", "third"));
        queue.mark("first", status, "TEST");
        queue.mark("second", "PENDING", "");
        queue.mark("third", status, "TEST");
      }
      assertEquals(
          "[\"first\",\"third\"]", Fetcher.results(status).getJSONArray("users").toString());
      try (QueueStore queue = new QueueStore(c)) {
        assertEquals(Arrays.asList("first", "second", "third"), queue.users(false));
      }
    }
    assertThrows(java.io.IOException.class, () -> Fetcher.results("anything"));
  }
}
