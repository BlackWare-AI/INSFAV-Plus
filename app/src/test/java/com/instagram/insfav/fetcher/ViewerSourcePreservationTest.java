package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.content.*;
import android.net.Uri;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ViewerSourcePreservationTest {
  @Test
  public void remoteViewerDoesNotCreate256WebpBesideAnExistingPngAndReleasesMemory()
      throws Exception {
    FetcherStorageTest harness = new FetcherStorageTest();
    harness.setup();
    Context c = harness.context;
    harness.provider.root.mkdir();
    File folder = new File(harness.provider.root, ".thumbnails");
    folder.mkdir();
    File original = new File(folder, "example.png");
    byte[] png = CodecTest.png(128, 128);
    Files.write(original.toPath(), png);
    long modified = original.lastModified();
    SessionState.prefs(c)
        .edit()
        .putString("tree", harness.tree.toString())
        .putString("directoryId", "root/.thumbnails")
        .putInt("minimum", 1)
        .putInt("maximum", 1)
        .commit();
    AtomicInteger written = new AtomicInteger();
    CountDownLatch checked = new CountDownLatch(1), ready = new CountDownLatch(1);
    Fetcher.initialize(
        c,
        new Fetcher.Host() {
          public Collection<String> users() {
            return Arrays.asList("example");
          }

          public String tree() {
            return harness.tree.toString();
          }

          public String directory() {
            return "root/.thumbnails";
          }

          public long revision() {
            return 0;
          }

          public boolean hasImage(String u) {
            throw new AssertionError("No redundant source decode");
          }

          public boolean catalogReadable(String u) {
            checked.countDown();
            return true;
          }

          public void imageWritten(String u, Uri uri, String tree) {
            written.incrementAndGet();
          }
        });
    byte[] remote = CodecTest.png(640, 480);
    FetchBroker broker =
        new FetchBroker(
            c,
            full ->
                new ProfileProvider() {
                  public byte[] fetch(String user) {
                    return remote;
                  }

                  public void cancel() {}
                },
            new TestTime());
    ReflectionHelpers.setStaticField(FetchBroker.class, "instance", broker);
    Files.write(new File(c.getFilesDir(), "fetcher-cookies.enc").toPath(), new byte[] {1});
    Fetcher.Listener listener =
        new Fetcher.Listener() {
          public void changed() {}

          public void fullReady(String user, String token, int width, int height) {
            ready.countDown();
          }

          public void fullFailed(String token, String code) {}
        };
    Fetcher.addListener(listener);
    try {
      Fetcher.view("example", "fixture");
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      assertTrue(checked.await(5, TimeUnit.SECONDS));
      assertEquals(0, written.get());
      assertFalse(new File(folder, "example.webp").exists());
      assertArrayEquals(png, Files.readAllBytes(original.toPath()));
      assertEquals(modified, original.lastModified());
      byte[] held = Fetcher.full("fixture");
      assertTrue(held.length > 0);
      Fetcher.closeViewer();
      assertEquals(0, Fetcher.full("fixture").length);
      for (byte b : held) assertEquals(0, b);
    } finally {
      Fetcher.removeListener(listener);
      Fetcher.closeViewer();
      broker.cancelAll();
    }
  }
}
