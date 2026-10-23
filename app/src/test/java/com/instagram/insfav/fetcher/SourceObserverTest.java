package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class SourceObserverTest {
  @Test
  public void coldGalleryObservesChangesWithoutQueueSyncOrFullCatalogTransfer() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    CountDownLatch changed = new CountDownLatch(1);
    AtomicLong revision = new AtomicLong(), catalogReads = new AtomicLong();
    Fetcher.initialize(
        c,
        new Fetcher.Host() {
          public Collection<String> users() {
            throw new AssertionError("Watching must not sync all usernames");
          }

          public String tree() {
            return "content://gallery.test/tree/root";
          }

          public String directory() {
            return "root/.thumbnails";
          }

          public long revision() {
            return 5;
          }

          public long sourceRevision() {
            return revision.get();
          }

          public void sourceChanged() {
            revision.incrementAndGet();
            changed.countDown();
          }

          public JSONObject catalog() {
            catalogReads.incrementAndGet();
            return new JSONObject();
          }

          public boolean hasImage(String user) {
            return false;
          }

          public void imageWritten(String user, Uri uri, String tree) {}
        });
    Fetcher.watchSource();
    c.getContentResolver()
        .notifyChange(Uri.parse("content://gallery.test/tree/root/document/example"), null);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertTrue(changed.await(5, TimeUnit.SECONDS));
    assertEquals(1, revision.get());
    assertEquals(0, catalogReads.get());
    assertEquals(1, Fetcher.snapshot().getLong("sourceRevision"));
  }
}
