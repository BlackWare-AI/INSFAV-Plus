package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ViewerThumbnailUpdateTest {
  FetcherStorageTest harness;
  Context context;
  File target;
  byte[] before;
  CountDownLatch written;

  @Before public void setup() throws Exception {
    harness = new FetcherStorageTest(); harness.setup(); context = harness.context;
    File folder = new File(harness.provider.root, ".thumbnails"); assertTrue(folder.mkdir());
    target = new File(folder, "example.webp");
    Files.write(target.toPath(), ThumbnailCodec.convert(CodecTest.png(400, 400)));
    before = ThumbnailUpdateTest.sha(target); written = new CountDownLatch(1);
    Fetcher.initialize(context, new Fetcher.Host() {
      public Collection<String> users() { return Arrays.asList("example"); }
      public String tree() { return harness.tree.toString(); }
      public String directory() { return "root/.thumbnails"; }
      public long revision() { return 0; }
      public boolean hasImage(String user) { return true; }
      public boolean catalogReadable(String user) { return true; }
      public void imageWritten(String user, Uri uri, String tree) { written.countDown(); }
    });
    SessionState.prefs(context).edit().putString("tree", harness.tree.toString())
        .putString("directoryId", "root/.thumbnails").putBoolean("manualSession", true).putInt("minimum", 1).putInt("maximum", 1).commit();
    Files.write(new File(context.getFilesDir(), "fetcher-cookies.enc").toPath(), new byte[] {1});
  }

  @After public void close() { Fetcher.closeViewer(); }

  @Test public void successfulHdFetchUpdatesExistingWebpThroughVerifiedWritePath() throws Exception {
    byte[] remote = CodecTest.png(640, 480, Color.BLUE);
    FetchBroker broker = new FetchBroker(context, full -> new ProfileProvider() {
      public byte[] fetch(String user) { return remote.clone(); }
      public void cancel() { }
    }, new TestTime());
    ReflectionHelpers.setStaticField(FetchBroker.class, "instance", broker);
    try {
      Fetcher.view("example", "updated");
      assertTrue(written.await(5, TimeUnit.SECONDS));
      assertFalse(Arrays.equals(before, ThumbnailUpdateTest.sha(target)));
      assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256")
          .digest(ThumbnailCodec.convert(remote)), ThumbnailUpdateTest.sha(target));
      assertTrue(ThumbnailCodec.valid(Files.readAllBytes(target.toPath())));
    } finally { broker.cancelAll(); }
  }

  @Test public void removedAvatarAccountHttpSessionAndTimeoutFailuresPreserveSha256() throws Exception {
    for (String code : new String[] {"PROFILE_PAGE_UNAVAILABLE", "PROFILE_IMAGE_URL_MISSING",
        "PROFILE_DEFAULT_AVATAR", "PROFILE_NOT_FOUND_OR_UNAVAILABLE", "WEBVIEW_TIMEOUT",
        "HTTP_ERROR", "COOKIE_INVALID_EXPIRED", "CHALLENGE_REQUIRED", "NETWORK_ERROR"}) {
      CountDownLatch failed = new CountDownLatch(1);
      Fetcher.Listener listener = new Fetcher.Listener() {
        public void changed() { }
        public void fullReady(String user, String token, int width, int height) { fail("No image expected"); }
        public void fullFailed(String token, String reason) { failed.countDown(); }
      };
      FetchBroker broker = new FetchBroker(context, full -> new ProfileProvider() {
        public byte[] fetch(String user) throws Failure { throw new Failure(code, code.equals("HTTP_ERROR") ? 500 : 0, 0, false); }
        public void cancel() { }
      }, new TestTime());
      ReflectionHelpers.setStaticField(FetchBroker.class, "instance", broker);
      Fetcher.addListener(listener);
      try {
        Fetcher.view("example", "failed");
        assertTrue(code, failed.await(5, TimeUnit.SECONDS));
        assertArrayEquals(code, before, ThumbnailUpdateTest.sha(target));
        assertEquals(1, written.getCount());
      } finally { Fetcher.removeListener(listener); Fetcher.closeViewer(); broker.cancelAll(); }
    }
    assertEquals(0, harness.provider.writes);
  }
}
