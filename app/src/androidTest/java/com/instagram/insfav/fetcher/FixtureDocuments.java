package com.instagram.insfav.fetcher;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import java.io.*;

/** Synthetic SAF provider exists only in the test APK. Never shipped in INSFAV. */
public final class FixtureDocuments extends DocumentsProvider {
  static final String AUTH = "com.instagram.insfav.plus.test.documents";

  static Uri tree() {
    return DocumentsContract.buildTreeDocumentUri(AUTH, "root");
  }

  File root;

  @Override
  public boolean onCreate() {
    root = new File(getContext().getFilesDir(), "fixture-thumbnails");
    return root.mkdirs() || root.isDirectory();
  }

  File file(String id) throws FileNotFoundException {
    if (id.equals("root")) return root;
    if (!id.startsWith("root/") || !id.substring(5).matches("[A-Za-z0-9._-]+"))
      throw new FileNotFoundException();
    return new File(root, id.substring(5));
  }

  static final String[] C = {
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_FLAGS,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED
  };

  void row(MatrixCursor c, String id) throws FileNotFoundException {
    File f = file(id);
    MatrixCursor.RowBuilder r = c.newRow();
    for (String n : c.getColumnNames()) {
      Object v = null;
      if (n.equals(C[0])) v = id;
      else if (n.equals(C[1])) v = id.equals("root") ? ".thumbnails" : f.getName();
      else if (n.equals(C[2]))
        v = f.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "image/webp";
      else if (n.equals(C[3]))
        v =
            f.isDirectory()
                ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                : DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                    | DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                    | DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
      else if (n.equals(C[4])) v = f.length();
      else if (n.equals(C[5])) v = f.lastModified();
      r.add(v);
    }
  }

  @Override
  public Cursor queryRoots(String[] projection) {
    MatrixCursor c =
        new MatrixCursor(
            new String[] {
              DocumentsContract.Root.COLUMN_ROOT_ID,
              DocumentsContract.Root.COLUMN_DOCUMENT_ID,
              DocumentsContract.Root.COLUMN_TITLE,
              DocumentsContract.Root.COLUMN_FLAGS
            });
    c.addRow(
        new Object[] {
          "root", "root", "Offline Thumbnail Fixture", DocumentsContract.Root.FLAG_SUPPORTS_CREATE
        });
    return c;
  }

  @Override
  public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
    MatrixCursor c = new MatrixCursor(projection == null ? C : projection);
    if (!file(id).exists()) throw new FileNotFoundException();
    row(c, id);
    return c;
  }

  @Override
  public Cursor queryChildDocuments(String id, String[] projection, String sort)
      throws FileNotFoundException {
    MatrixCursor c = new MatrixCursor(projection == null ? C : projection);
    File[] fs = file(id).listFiles();
    if (fs != null) for (File f : fs) row(c, "root/" + f.getName());
    return c;
  }

  @Override
  public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
  }

  @Override
  public boolean isChildDocument(String parent, String child) {
    return parent.equals("root") && child.startsWith("root/");
  }

  @Override
  public String createDocument(String parent, String mime, String name)
      throws FileNotFoundException {
    try {
      String id = "root/" + name;
      File f = file(id);
      if (!f.createNewFile()) throw new IOException();
      return id;
    } catch (IOException e) {
      throw new FileNotFoundException();
    }
  }

  @Override
  public String renameDocument(String id, String name) throws FileNotFoundException {
    String to = "root/" + name;
    if (!file(id).renameTo(file(to))) throw new FileNotFoundException();
    return to;
  }

  @Override
  public void deleteDocument(String id) throws FileNotFoundException {
    if (!file(id).delete()) throw new FileNotFoundException();
  }

  public static final class Grant extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
      c.grantUriPermission(
          "com.instagram.insfav.plus",
          tree(),
          Intent.FLAG_GRANT_READ_URI_PERMISSION
              | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
              | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
              | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }
  }
}
