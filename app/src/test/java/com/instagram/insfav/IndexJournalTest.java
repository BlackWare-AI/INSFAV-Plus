package com.instagram.insfav;

import static org.junit.Assert.*;

import android.content.Context;
import android.util.AtomicFile;
import com.instagram.insfav.fetcher.IndexJournal;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class IndexJournalTest {
  Context context;
  Store store;
  String tree = "content://fixture/tree/root", scope = tree + "\nroot";

  @Before
  public void setup() throws Exception {
    context = RuntimeEnvironment.getApplication();
    context.deleteDatabase("fetcher-queue.db");
    store = new Store(context);
    store.settings.put("treeUri", tree).put("directoryId", "root");
    store.saveSettings();
    store.replaceIndex(
        new JSONObject()
            .put("schema", 1)
            .put("treeUri", tree)
            .put("revision", 10)
            .put("files", 0)
            .put("bytes", 0)
            .put("profiles", new JSONObject()));
  }

  JSONObject profile() throws Exception {
    return new JSONObject()
        .put("uri", "content://fixture/document/one")
        .put("name", "one.webp")
        .put("readable", true)
        .put("width", 256)
        .put("height", 256)
        .put("size", 123)
        .put("modified", 1);
  }

  JSONObject disk() throws Exception {
    return new JSONObject(
        new String(
            new AtomicFile(new File(store.root, "thumbnail-index.json")).readFully(),
            StandardCharsets.UTF_8));
  }

  @Test
  public void oneChangeIsDurableWithoutRewritingTheSnapshot() throws Exception {
    store.updateProfile("one", profile(), tree);
    assertFalse(disk().getJSONObject("profiles").has("one"));
    try (IndexJournal j = new IndexJournal(context)) {
      assertEquals(1, j.pending(scope).size());
    }
    assertTrue(store.index.getJSONObject("profiles").has("one"));
    store.flushIndex();
    assertTrue(disk().getJSONObject("profiles").has("one"));
    assertEquals(1, disk().getInt("files"));
  }

  @Test
  public void crashAfterDurableDeltaBeforeMemoryUpdateRecoversTheImage() throws Exception {
    try (IndexJournal j = new IndexJournal(context)) {
      j.record(scope, "one", profile(), 11);
    }
    Store recovered = new Store(context);
    assertTrue(recovered.index.getJSONObject("profiles").has("one"));
    assertEquals(123, recovered.index.getLong("bytes"));
    assertEquals(1, recovered.index.getInt("files"));
    try (IndexJournal j = new IndexJournal(context)) {
      assertTrue(j.pending(scope).isEmpty());
    }
  }

  @Test
  public void everyAcknowledgedChangeBeforeTheBatchThresholdSurvivesRestart() throws Exception {
    for (int i = 0; i < 63; i++) {
      String user = "durable" + i;
      store.updateProfile(
          user,
          profile().put("name", user + ".webp").put("uri", "content://fixture/document/" + user),
          tree);
    }
    assertEquals(0, disk().getJSONObject("profiles").length());
    // Model the first process stopping, including its in-memory timer.
    java.util.concurrent.ScheduledFuture<?> scheduled =
        org.robolectric.util.ReflectionHelpers.getField(store, "pendingSnapshot");
    if (scheduled != null) scheduled.cancel(false);
    Store recovered = new Store(context);
    assertEquals(63, recovered.index.getJSONObject("profiles").length());
    assertEquals(63, recovered.index.getInt("files"));
    for (int i = 0; i < 63; i++)
      assertTrue(
          recovered
              .index
              .getJSONObject("profiles")
              .getJSONObject("durable" + i)
              .getBoolean("readable"));
    assertEquals(63, disk().getJSONObject("profiles").length());
    try (IndexJournal journal = new IndexJournal(context)) {
      assertTrue(journal.pending(scope).isEmpty());
    }
    assertEquals(recovered.index.toString(), new Store(context).index.toString());
  }

  @Test
  public void crashAfterAtomicSnapshotBeforeAcknowledgmentDoesNotApplyTwice() throws Exception {
    store.updateProfile("one", profile(), tree);
    store.flushIndex();
    long revision = store.index.getLong("revision");
    try (IndexJournal j = new IndexJournal(context)) {
      j.record(scope, "one", profile(), revision);
    }
    Store recovered = new Store(context);
    assertEquals(1, recovered.index.getInt("files"));
    assertEquals(123, recovered.index.getLong("bytes"));
    assertEquals(0, recovered.writeMetrics().getInt("recoveredDeltas"));
    try (IndexJournal j = new IndexJournal(context)) {
      assertTrue(j.pending(scope).isEmpty());
    }
  }

  @Test
  public void unfinishedAtomicWriteRestoresOldSnapshotThenReplaysDelta() throws Exception {
    try (IndexJournal j = new IndexJournal(context)) {
      j.record(scope, "one", profile(), 11);
    }
    AtomicFile file = new AtomicFile(new File(store.root, "thumbnail-index.json"));
    FileOutputStream output = file.startWrite();
    output.write("broken".getBytes(StandardCharsets.UTF_8));
    output.close();
    Store recovered = new Store(context);
    assertEquals(1, recovered.index.getInt("files"));
    assertTrue(recovered.index.getJSONObject("profiles").has("one"));
  }

  @Test
  public void fullIndexReplacementClearsPreviousScopeDeltas() throws Exception {
    store.updateProfile("one", profile(), tree);
    store.replaceIndex(new JSONObject());
    Store reopened = new Store(context);
    assertFalse(reopened.index.has("profiles"));
  }

  @Test
  public void sixtyFourUpdatesProduceOneAdditionalFullSnapshot() throws Exception {
    long before = store.writeMetrics().getLong("snapshotWrites");
    for (int i = 0; i < 64; i++)
      store.updateProfile(
          "account" + i,
          new JSONObject(profile().toString()).put("name", "account" + i + ".webp"),
          tree);
    assertEquals(before + 1, store.writeMetrics().getLong("snapshotWrites"));
    assertEquals(64, disk().getInt("files"));
    try (IndexJournal j = new IndexJournal(context)) {
      assertTrue(j.pending(scope).isEmpty());
    }
  }
  @Test public void durableJournalUsesFullSynchronousWalBeforeAcknowledgment() throws Exception {
    try (IndexJournal journal = new IndexJournal(context)) {
      journal.record(scope, "one", profile(), 11);
      android.database.sqlite.SQLiteOpenHelper helper = org.robolectric.util.ReflectionHelpers.getField(journal, "queue");
      try (android.database.Cursor cursor = helper.getWritableDatabase().rawQuery("PRAGMA synchronous", null)) {
        assertTrue(cursor.moveToFirst());assertEquals(2, cursor.getInt(0));
      }
    }
  }
}
