package com.instagram.insfav;

import static org.junit.Assert.*;

import android.content.*;
import android.net.Uri;
import java.io.*;
import java.nio.file.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class InventoryTest {
  Context context;
  Store store;
  LocalImages images;
  LocalImagesTest.FixtureProvider provider;
  ThumbnailInventory inventory;

  @Before
  public void setup() throws Exception {
    LocalImagesTest harness = new LocalImagesTest();
    harness.setup();
    context = ReflectionHelpers.getField(harness, "context");
    store = ReflectionHelpers.getField(harness, "store");
    images = ReflectionHelpers.getField(harness, "images");
    provider = ReflectionHelpers.getField(harness, "provider");
    images.select(
        Uri.parse("content://insfav.test/tree/root"), Intent.FLAG_GRANT_READ_URI_PERMISSION);
    inventory = new ThumbnailInventory(context, store, images);
  }

  @After
  public void close() {
    inventory.close();
  }

  @Test
  public void missingPresentAndOrphanViewsAreReadOnlyAndDoNotChangeMemberships() throws Exception {
    provider.add("root/orphan.webp", "orphan.webp", 256, 256);
    File source = provider.files.get("root/orphan.webp");
    byte[] original = Files.readAllBytes(source.toPath());
    long modified = source.lastModified();
    String document = store.original();
    assertFalse(inventory.query("orphan", 0, "").getBoolean("complete"));
    images.scan((e, v) -> {});
    int opens = provider.opens;
    JSONObject missing = inventory.query("missing", 0, "");
    assertEquals(1, missing.getInt("total"));
    assertEquals("missing", missing.getJSONArray("items").getJSONObject(0).getString("user"));
    assertEquals(1, inventory.query("present", 0, "").getInt("total"));
    JSONObject orphan = inventory.query("orphan", 0, "orphan");
    assertEquals(1, orphan.getInt("total"));
    assertEquals(opens, provider.opens);
    JSONObject item = orphan.getJSONArray("items").getJSONObject(0);
    assertFalse(item.toString().contains("content://"));
    try (InputStream in = inventory.open(item.getString("preview"))) {
      assertArrayEquals(original, in.readAllBytes());
    }
    assertArrayEquals(original, Files.readAllBytes(source.toPath()));
    assertEquals(modified, source.lastModified());
    assertEquals(document, store.original());
    assertEquals(0, provider.writes);
  }

  @Test
  public void unloadedCollectionsCannotMakeEveryImageAConfirmedOrphan() throws Exception {
    images.scan((e, v) -> {});
    store.clearDocument();
    int opens = provider.opens;
    JSONObject result = inventory.query("orphan", 0, "");
    assertFalse(result.getBoolean("collectionLoaded"));
    assertFalse(result.getBoolean("complete"));
    assertTrue(result.getBoolean("relativeToLoadedCollections"));
    assertTrue(result.getBoolean("readOnly"));
    assertEquals(0, result.getJSONArray("items").length());
    assertEquals(opens, provider.opens);
    assertEquals(0, provider.writes);
  }

  @Test
  public void paginationIsBoundedAndOldPreviewTokensExpire() throws Exception {
    for (int i = 0; i < 105; i++)
      provider.add("root/orphan" + i + ".webp", "orphan" + i + ".webp", 128, 128);
    images.scan((e, v) -> {});
    JSONObject first = inventory.query("orphan", 0, "orphan");
    assertEquals(105, first.getInt("total"));
    assertEquals(100, first.getJSONArray("items").length());
    String token = first.getJSONArray("items").getJSONObject(0).getString("preview");
    assertEquals(5, inventory.query("orphan", 100, "orphan").getJSONArray("items").length());
    assertThrows(IOException.class, () -> inventory.open(token));
  }

  @Test
  public void lostAccessInvalidatesPreviewAndMissingViewDoesNotUseStaleImages() throws Exception {
    images.scan((e, v) -> {});
    JSONObject present = inventory.query("present", 0, "");
    String token = present.getJSONArray("items").getJSONObject(0).getString("preview");
    context
        .getContentResolver()
        .releasePersistableUriPermission(
            Uri.parse("content://insfav.test/tree/root"), Intent.FLAG_GRANT_READ_URI_PERMISSION);
    assertThrows(IOException.class, () -> inventory.open(token));
    assertEquals(2, inventory.query("missing", 0, "").getInt("total"));
  }

  @Test
  public void historyIsBoundedAndRejectsUnstructuredOrPrivateText() throws Exception {
    StringBuilder lines = new StringBuilder();
    for (int i = 0; i < 240; i++) lines.append(1700000000000L + i).append(" PROFILE SUCCESS 0\n");
    lines.append(
        "1700000000241 PROFILE PRIVATE_USER 0\n"
            + "1700000000242 URL https://private.test/token\n"
            + "secret cookie\n");
    Files.write(
        new File(context.getFilesDir(), "fetcher-diagnostics.txt").toPath(),
        lines.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    JSONArray history = OperationHistory.read(context);
    assertEquals(200, history.length());
    assertFalse(history.toString().contains("PRIVATE_USER"));
    assertFalse(history.toString().contains("secret"));
    assertFalse(history.toString().contains("private.test"));
  }

  @Test
  public void performanceDistinguishesAbsentSamplesAndComparesCompletedScans() throws Exception {
    JSONObject fetcher = new JSONObject().put("activeViews", 0).put("effectiveViews", 1);
    assertFalse(
        PerformanceReport.read(store, images, fetcher)
            .getJSONArray("metrics")
            .getJSONObject(0)
            .getBoolean("measured"));
    images.scan((e, v) -> {});
    JSONObject first = new JSONObject(store.settings.getJSONObject("lastScanMetrics").toString());
    images.scan((e, v) -> {});
    assertEquals(first.toString(), store.settings.getJSONObject("previousScanMetrics").toString());
    assertTrue(
        PerformanceReport.read(store, images, fetcher)
            .getJSONArray("metrics")
            .getJSONObject(0)
            .getBoolean("measured"));
  }

  @Test
  public void restoringAnUngrantableFolderCannotKeepDisplayingThePreviouslySelectedFolder()
      throws Exception {
    images.scan((e, v) -> {});
    String document = store.original();
    MainActivity.restoreFolderSelection(
        store,
        new JSONObject()
            .put("treeUri", "content://other.test/tree/new")
            .put("directoryId", "new")
            .put("folderLabel", "Imported folder"));
    images.checkAccess();
    assertEquals("unavailable", images.snapshot(false).getString("folderStatus"));
    assertEquals(0, images.snapshot(true).getJSONObject("profiles").length());
    assertEquals(document, store.original());
    assertEquals(0, provider.writes);
  }

  @Test
  public void restoringAnotherDirectoryUnderTheSameSafTreeInvalidatesOldMetadata()
      throws Exception {
    images.scan((e, v) -> {});
    assertTrue(store.index.getJSONObject("profiles").length() > 0);
    String tree = store.settings.getString("treeUri");
    MainActivity.restoreFolderSelection(
        store, new JSONObject().put("treeUri", tree).put("directoryId", "root/another"));
    assertFalse(store.index.has("profiles"));
    assertEquals("root/another", store.settings.getString("directoryId"));
    assertEquals(0, provider.writes);
  }
}
