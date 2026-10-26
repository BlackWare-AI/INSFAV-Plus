package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FetcherStorageTest {
  Context context;
  Fixture provider;
  Uri tree = Uri.parse("content://fetcher.test/tree/root");

  @Before
  public void setup() throws Exception {
    context = RuntimeEnvironment.getApplication();
    ProviderInfo info = new ProviderInfo();
    info.authority = "fetcher.test";
    info.exported = true;
    info.grantUriPermissions = true;
    info.readPermission = "android.permission.MANAGE_DOCUMENTS";
    info.writePermission = "android.permission.MANAGE_DOCUMENTS";
    provider = Robolectric.buildContentProvider(Fixture.class).create(info).get();
    provider.root = Files.createTempDirectory("fetcher-saf").toFile();
    context
        .getContentResolver()
        .takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  }

  @Test
  public void writesSixValidatedProfilesAndPreservesOtherFiles() throws Exception {
    SessionState.prefs(context).edit().putString("directoryId", "root/.thumbnails").commit();
    File folder = new File(provider.root, ".thumbnails");
    folder.mkdir();
    byte[] keep = "untouched".getBytes();
    Files.write(new File(folder, "unrelated.txt").toPath(), keep);
    OutputFolder output = new OutputFolder(context, tree.toString());
    for (int i = 0; i < 6; i++) {
      byte[] thumb = ThumbnailCodec.convert(CodecTest.png(200 + i, 300 + i));
      assertNotNull(output.commit("example" + i + ".webp", thumb, false));
      assertTrue(output.valid("example" + i + ".webp"));
    }
    assertArrayEquals(keep, Files.readAllBytes(new File(folder, "unrelated.txt").toPath()));
  }

  @Test
  public void validImageIsPreservedWithoutAttemptingRename() throws Exception {
    SessionState.prefs(context).edit().putString("directoryId", "root/.thumbnails").commit();
    new File(provider.root, ".thumbnails").mkdir();
    OutputFolder output = new OutputFolder(context, tree.toString());
    byte[] old = ThumbnailCodec.convert(CodecTest.png(160, 160));
    output.commit("example.webp", old, false);
    provider.rejectRename = true;
    output.commit("example.webp", ThumbnailCodec.convert(CodecTest.png(300, 200)), false);
    assertArrayEquals(
        old, Files.readAllBytes(new File(provider.root, ".thumbnails/example.webp").toPath()));
  }

  @Test
  public void validExistingImageAndUnrelatedPhotosArePreserved() throws Exception {
    SessionState.prefs(context).edit().putString("directoryId", "root/.thumbnails").commit();
    new File(provider.root, ".thumbnails").mkdir();
    OutputFolder output = new OutputFolder(context, tree.toString());
    byte[] old = ThumbnailCodec.convert(CodecTest.png(160, 160));
    output.commit("example.webp", old, false);
    output.commit("other.webp", old, false);
    byte[] next = ThumbnailCodec.convert(CodecTest.png(300, 200));
    output.commit("example.webp", next, true);
    assertArrayEquals(
        old, Files.readAllBytes(new File(provider.root, ".thumbnails/example.webp").toPath()));
    assertArrayEquals(
        old, Files.readAllBytes(new File(provider.root, ".thumbnails/other.webp").toPath()));
  }

  @Test
  public void caseAndAtVariantsOfLegacyFilesAreProtected() throws Exception {
    SessionState.prefs(context).edit().putString("directoryId", "root/.thumbnails").commit();
    File folder = new File(provider.root, ".thumbnails");
    folder.mkdir();
    android.graphics.Bitmap b =
        android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    b.compress(android.graphics.Bitmap.CompressFormat.WEBP, 78, bytes);
    b.recycle();
    byte[] old = bytes.toByteArray();
    File one = new File(folder, "Example.WebP"), two = new File(folder, "@EXAMPLE.webp");
    Files.write(one.toPath(), old);
    Files.write(two.toPath(), old);
    OutputFolder out = new OutputFolder(context, tree.toString());
    assertTrue(out.valid("example.webp"));
    out.commit("example.webp", ThumbnailCodec.convert(CodecTest.png(320, 320)), false);
    assertArrayEquals(old, Files.readAllBytes(one.toPath()));
    assertArrayEquals(old, Files.readAllBytes(two.toPath()));
    assertFalse(new File(folder, "example.webp").exists());
  }

  @Test
  public void noWriteGrantStopsBeforeCreatingAnyFile() throws Exception {
    context
        .getContentResolver()
        .releasePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    try {
      new OutputFolder(context, tree.toString());
      fail();
    } catch (SecurityException expected) {
    }
    assertEquals(0, provider.writes);
  }

  @Test
  public void corruptExistingSourceBytesAndDateArePreserved() throws Exception {
    SessionState.prefs(context).edit().putString("directoryId", "root/.thumbnails").commit();
    File folder = new File(provider.root, ".thumbnails");
    folder.mkdir();
    File existing = new File(folder, "example.webp");
    byte[] original = {1, 2, 3, 4};
    Files.write(existing.toPath(), original);
    assertTrue(existing.setLastModified(123450000L));
    long modified = existing.lastModified();
    OutputFolder output = new OutputFolder(context, tree.toString());
    java.io.IOException error =
        assertThrows(
            java.io.IOException.class,
            () ->
                output.commit(
                    "example.webp", ThumbnailCodec.convert(CodecTest.png(320, 320)), false));
    assertEquals("OUTPUT_EXISTING_PRESERVED", error.getMessage());
    assertArrayEquals(original, Files.readAllBytes(existing.toPath()));
    assertEquals(modified, existing.lastModified());
    assertEquals(0, provider.writes);
    assertEquals(1, folder.list().length);
  }


    @Test public void interruptedJournalRestoresOldOrKeepsVerifiedFinal() throws Exception {
        byte[] old = ThumbnailCodec.convert(CodecTest.png(300, 300));
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.BLUE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 78, out);
        bitmap.recycle();
        byte[] next = out.toByteArray();
        assertFalse(Arrays.equals(old, next));
        for (int phase = 0; phase < 3; phase++) {
            String directory = "root/recovery" + phase;
            SessionState.prefs(context).edit().putString("directoryId", directory).commit();
            File folder = new File(provider.root, "recovery" + phase);
            assertTrue(folder.mkdir());
            String nonce = java.util.UUID.randomUUID().toString();
            String backupName = ".insfav-" + nonce + ".bak", pendingName = ".insfav-" + nonce + ".part";
            File backup = new File(folder, backupName), pending = new File(folder, pendingName), target = new File(folder, "example.webp");
            Files.write(backup.toPath(), old);
            assertTrue(backup.setLastModified(123450000L));
            long modified = backup.lastModified();
            Files.write(pending.toPath(), next);
            if (phase == 1) Files.write(target.toPath(), next);
            if (phase == 2) Files.write(target.toPath(), new byte[] {1, 2, 3});
            org.json.JSONObject value = new org.json.JSONObject()
                .put("tree", tree.toString())
                .put("folder", DocumentsContract.buildDocumentUriUsingTree(tree, directory).toString())
                .put("name", "example.webp").put("pending", pendingName).put("backup", backupName);
            android.util.AtomicFile journal = new android.util.AtomicFile(new File(context.getFilesDir(), "fetcher-write-journal.json"));
            FileOutputStream journalOut = journal.startWrite();
            journalOut.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            journal.finishWrite(journalOut);
            new OutputFolder(context, tree.toString());
            assertArrayEquals(phase == 1 ? next : old, Files.readAllBytes(target.toPath()));
            if (phase != 1) assertEquals(modified, target.lastModified());
            assertFalse(backup.exists());
            assertFalse(pending.exists());
            assertFalse(journal.getBaseFile().exists());
        }
    }

  public static class Fixture extends DocumentsProvider {
    boolean rejectFinalRename, corruptFinal, crashAfterBackup, crashAfterFinal, rejectBackupDelete, rejectRestore;
    File root;
    int writes, opens, childQueries;
    boolean rejectRename;
    static final String[] DEFAULT = {
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    File file(String id) {
      return id.equals("root") ? root : new File(root, id.substring(5));
    }

    String id(File file) {
      String rel = root.toPath().relativize(file.toPath()).toString();
      return rel.isEmpty() ? "root" : "root/" + rel;
    }

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
      return new MatrixCursor(
          projection == null ? new String[] {DocumentsContract.Root.COLUMN_ROOT_ID} : projection);
    }

    @Override
    public Cursor queryDocument(String id, String[] projection) {
      MatrixCursor c = new MatrixCursor(projection == null ? DEFAULT : projection);
      row(c, file(id));
      return c;
    }

    @Override
    public Cursor queryChildDocuments(String parent, String[] projection, String sort) {
      childQueries++;
      MatrixCursor c = new MatrixCursor(projection == null ? DEFAULT : projection);
      File[] files = file(parent).listFiles();
      if (files != null) for (File f : files) row(c, f);
      return c;
    }

    void row(MatrixCursor cursor, File f) {
      Object[] data = new Object[cursor.getColumnCount()];
      String[] columns = cursor.getColumnNames();
      for (int i = 0; i < columns.length; i++) {
        if (columns[i].equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) data[i] = id(f);
        else if (columns[i].equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
          data[i] = f.getName();
        else if (columns[i].equals(DocumentsContract.Document.COLUMN_MIME_TYPE))
          data[i] = f.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "image/webp";
      }
      cursor.addRow(data);
    }

    @Override
    public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
        throws FileNotFoundException {
      if (!mode.equals("r")) writes++;
      else opens++;
      return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parent, String mime, String name)
        throws FileNotFoundException {
      File file = new File(file(parent), name);
      try {
        if (mime.equals(DocumentsContract.Document.MIME_TYPE_DIR)) {
          if (!file.mkdir()) throw new IOException();
        } else if (!file.createNewFile()) throw new IOException();
      } catch (IOException error) {
        throw new FileNotFoundException();
      }
      return id(file);
    }

    @Override
    public String renameDocument(String old, String name) throws FileNotFoundException {
      if (rejectRename) throw new UnsupportedOperationException();
      if (rejectFinalRename && old.endsWith(".part")) throw new FileNotFoundException("final rename rejected");
      if (rejectRestore && old.endsWith(".bak")) throw new FileNotFoundException("restore temporarily unavailable");
      File target = new File(file(old).getParentFile(), name);
      if (!file(old).renameTo(target)) throw new FileNotFoundException();
      if (crashAfterBackup && name.endsWith(".bak")) throw new AssertionError("simulated process exit after backup");
      if (old.endsWith(".part")) {
        if (corruptFinal) {
          try { Files.write(target.toPath(), new byte[] {1,2,3}); }
          catch (IOException error) { throw new FileNotFoundException(); }
        }
        if (crashAfterFinal) throw new AssertionError("simulated process exit after final rename");
      }
      return id(target);
    }

    @Override
    public void deleteDocument(String id) throws FileNotFoundException {
      if (rejectBackupDelete && id.endsWith(".bak")) throw new FileNotFoundException("backup cleanup rejected");
      if (!file(id).delete()) throw new FileNotFoundException();
    }

    @Override
    public boolean isChildDocument(String parent, String child) {
      return child.startsWith(parent + "/");
    }
  }
}
