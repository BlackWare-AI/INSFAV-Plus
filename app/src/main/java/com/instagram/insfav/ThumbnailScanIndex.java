package com.instagram.insfav;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.File;
import java.util.*;

/** Private, replaceable scan checkpoints. Never writes the SAF source or the Fetcher database. */
final class ThumbnailScanIndex extends SQLiteOpenHelper {
  long generation;
  int transactions;
  long transactionNanos;
  boolean resumed;
  // Retain compact local row IDs, not thousands of potentially long SAF document IDs/URIs.
  private final Set<Long> seen = new HashSet<>();
  private final List<Long> removed = new ArrayList<>();

  ThumbnailScanIndex(Context context, File privateRoot) {
    super(context, new File(privateRoot, "thumbnail-scan.db").getAbsolutePath(), null, 1);
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE state (id INTEGER PRIMARY KEY,scope TEXT NOT NULL,generation INTEGER NOT"
            + " NULL,completed INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE entries (document_id TEXT PRIMARY KEY,name TEXT NOT NULL,uri TEXT NOT"
            + " NULL,size INTEGER NOT NULL,modified INTEGER NOT NULL,width INTEGER NOT NULL,height"
            + " INTEGER NOT NULL,readable INTEGER NOT NULL,reusable INTEGER NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    throw new SQLiteException("Unsupported thumbnail checkpoint schema");
  }

  void begin(String scope) {
    seen.clear();
    removed.clear();
    resumed = false;
    SQLiteDatabase db = getWritableDatabase();
    long transactionStarted = System.nanoTime();
    db.beginTransaction();
    try {
      generation = 1;
      try (Cursor c =
          db.rawQuery("SELECT scope,generation,completed FROM state WHERE id=1", null)) {
        if (c.moveToFirst() && scope.equals(c.getString(0))) {
          generation = c.getLong(1) + 1;
          resumed = c.getInt(2) == 0;
        } else db.delete("entries", null, null);
      }
      db.execSQL("INSERT OR REPLACE INTO state VALUES (1,?,?,0)", new Object[] {scope, generation});
      db.setTransactionSuccessful();
      transactions++;
    } finally {
      db.endTransaction();
      transactionNanos += System.nanoTime() - transactionStarted;
    }
  }

  Map<String, Entry> lookup(List<Entry> batch) {
    String[] ids = new String[batch.size()];
    Arrays.setAll(ids, i -> batch.get(i).id);
    String placeholders = String.join(",", Collections.nCopies(ids.length, "?"));
    Map<String, Entry> result = new HashMap<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT document_id,name,uri,size,modified,width,height,readable,reusable,rowid"
                    + " FROM entries WHERE document_id IN ("
                    + placeholders
                    + ")",
                ids)) {
      while (c.moveToNext()) {
        Entry e =
            new Entry(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4));
        e.width = c.getInt(5);
        e.height = c.getInt(6);
        e.readable = c.getInt(7) != 0;
        e.reusable = c.getInt(8) != 0;
        e.rowId = c.getLong(9);
        result.put(e.id, e);
      }
    }
    return result;
  }

  void save(List<Entry> batch) {
    if (batch.isEmpty()) return;
    boolean changed = false;
    for (Entry e : batch) {
      if (e.cached) seen.add(e.rowId);
      else changed = true;
    }
    // Unchanged rows need no durable write. Keep only compact row IDs for this listing.
    if (!changed) return;
    SQLiteDatabase db = getWritableDatabase();
    long transactionStarted = System.nanoTime();
    db.beginTransaction();
    List<Long> inserted = new ArrayList<>();
    try (SQLiteStatement insert =
        db.compileStatement("INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?,?,?,?,?)")) {
      for (Entry e : batch) {
        if (!e.cached) {
          insert.bindString(1, e.id);
          insert.bindString(2, e.name);
          insert.bindString(3, e.uri);
          insert.bindLong(4, e.size);
          insert.bindLong(5, e.modified);
          insert.bindLong(6, e.width);
          insert.bindLong(7, e.height);
          insert.bindLong(8, e.readable ? 1 : 0);
          insert.bindLong(9, e.reusable ? 1 : 0);
          inserted.add(insert.executeInsert());
        }
      }
      db.setTransactionSuccessful();
      transactions++;
    } finally {
      db.endTransaction();
      transactionNanos += System.nanoTime() - transactionStarted;
    }
    seen.addAll(inserted);
  }

  ThumbnailPolicy.Catalog catalog() {
    ThumbnailPolicy.Catalog out = new ThumbnailPolicy.Catalog();
    removed.clear();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT rowid,name,uri,width,height,size,modified,readable FROM entries", null)) {
      while (c.moveToNext()) {
        if (seen.contains(c.getLong(0)))
          out.add(
              new ThumbnailPolicy.Candidate(
                  c.getString(1),
                  c.getString(2),
                  c.getInt(3),
                  c.getInt(4),
                  c.getLong(5),
                  c.getLong(6),
                  c.getInt(7) != 0));
        else removed.add(c.getLong(0));
      }
    }
    return out;
  }

  /** Only after the complete listing AND atomic public JSON snapshot succeeded. */
  org.json.JSONObject resolution(String scope) throws org.json.JSONException {
    // One SQLite snapshot: never combine a completed flag with a newer partial batch.
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT SUM(CASE WHEN width>0 AND height>0 AND (width!=256 OR height!=256) THEN 1"
                    + " ELSE 0 END),SUM(CASE WHEN width<=0 OR height<=0 THEN 1 ELSE 0 END) FROM"
                    + " state LEFT JOIN entries ON 1=1 WHERE state.id=1 AND scope=? AND completed=1"
                    + " GROUP BY state.id",
                new String[] {scope})) {
      return c.moveToFirst() ? ResolutionAudit.result(c.getInt(0), c.getInt(1), true) : null;
    }
  }

  void complete() {
    SQLiteDatabase db = getWritableDatabase();
    long transactionStarted = System.nanoTime();
    db.beginTransaction();
    try (SQLiteStatement delete = db.compileStatement("DELETE FROM entries WHERE rowid=?")) {
      for (long id : removed) {
        delete.bindLong(1, id);
        delete.executeUpdateDelete();
      }
      db.execSQL("UPDATE state SET completed=1 WHERE id=1");
      db.setTransactionSuccessful();
      transactions++;
    } finally {
      db.endTransaction();
      transactionNanos += System.nanoTime() - transactionStarted;
    }
  }

  void clear() {
    SQLiteDatabase db = getWritableDatabase();
    long transactionStarted = System.nanoTime();
    db.beginTransaction();
    try {
      db.delete("entries", null, null);
      db.delete("state", null, null);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
      transactionNanos += System.nanoTime() - transactionStarted;
    }
  }

  static final class Entry {
    final String id, name, uri;
    final long size, modified;
    long rowId;
    int width, height;
    boolean readable, cached, reusable = true;

    Entry(String id, String name, String uri, long size, long modified) {
      this.id = id;
      this.name = name;
      this.uri = uri;
      this.size = size;
      this.modified = modified;
    }

    boolean matches(Entry e) {
      return e != null
          && e.reusable
          && size >= 0
          && modified > 0
          && size == e.size
          && modified == e.modified
          && name.equals(e.name)
          && uri.equals(e.uri);
    }

    void reuse(Entry e) {
      width = e.width;
      height = e.height;
      readable = e.readable;
      rowId = e.rowId;
      cached = true;
    }
  }
}
