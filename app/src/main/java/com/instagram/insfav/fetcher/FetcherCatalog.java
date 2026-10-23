package com.instagram.insfav.fetcher;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

/** Fetcher-only durable metadata. Never edits the normal INSFAV catalog or source images. */
final class FetcherCatalog {
  static final Set<String> writing = java.util.concurrent.ConcurrentHashMap.newKeySet();
  static final java.util.concurrent.ConcurrentHashMap<String, Long> ownUris =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicLong listings = new AtomicLong(),
      opens = new AtomicLong(),
      decodes = new AtomicLong(),
      hits = new AtomicLong(),
      writes = new AtomicLong();

  static String scope() {
    return Fetcher.host().tree() + "\n" + Fetcher.host().directory();
  }

  static boolean ready(QueueStore q, String scope) {
    return scope.equals(q.meta("folder")) && "1".equals(q.meta("ready"));
  }

  static boolean seed(QueueStore q) throws Exception {
    String scope = scope(), revision = Long.toString(Fetcher.host().revision());
    if (ready(q, scope) && revision.equals(q.meta("sourceRevision"))) return true;
    JSONObject source = Fetcher.host().catalog();
    JSONObject profiles = source.optJSONObject("profiles");
    if (profiles == null || !Fetcher.host().tree().equals(source.optString("treeUri")))
      return false;
    android.database.sqlite.SQLiteDatabase db = q.getWritableDatabase();
    db.beginTransaction();
    try {
      if (!scope.equals(q.meta("folder"))) {
        db.delete("fetch_images", null, null);
        db.execSQL(
            "UPDATE accounts SET status='PENDING',phase='PENDING',attempts=0,code='' WHERE status"
                + " IN ('SUCCESS','EXISTING')");
      }
      Set<String> current = new HashSet<>();
      for (Iterator<String> it = profiles.keys(); it.hasNext(); ) {
        String user = it.next();
        if (!Identity.valid(user)) continue;
        JSONObject p = profiles.getJSONObject(user);
        current.add(user);
        QueueStore.Image old = q.image(user);
        QueueStore.Image next =
            new QueueStore.Image(
                user,
                p.optString("uri"),
                p.optString("name"),
                p.optLong("size", -1),
                p.optInt("width"),
                p.optInt("height"),
                p.optLong("modified"),
                p.optBoolean("readable"));
        if (old == null || !same(old, next)) {
          q.image(next);
          writes.incrementAndGet();
          if (!next.readable) q.requeueLost(user);
        }
      }
      List<String> old = new ArrayList<>();
      try (Cursor c = db.rawQuery("SELECT username FROM fetch_images WHERE uri!=''", null)) {
        while (c.moveToNext()) if (!current.contains(c.getString(0))) old.add(c.getString(0));
      }
      for (String user : old) {
        q.image(new QueueStore.Image(user, "", "", 0, 0, 0, 0, false));
        q.requeueLost(user);
      }
      q.meta("folder", scope);
      q.meta("ready", "1");
      q.meta("sourceRevision", revision);
      q.meta("lastIndex", Long.toString(System.currentTimeMillis()));
      q.meta("verificationRecommended", "0");
      q.settleImages();
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    return true;
  }

  private static boolean same(QueueStore.Image a, QueueStore.Image b) {
    return a.uri.equals(b.uri)
        && a.name.equals(b.name)
        && a.size == b.size
        && a.width == b.width
        && a.height == b.height
        && a.modified == b.modified
        && a.readable == b.readable
        && !a.needsCheck;
  }

  static JSONObject resolution(QueueStore q, int offset) throws Exception {
    JSONObject out =
        new JSONObject()
            .put(
                "expected",
                ThumbnailCodec.LEGACY_SIZE
                    + "×"
                    + ThumbnailCodec.LEGACY_SIZE
                    + " or "
                    + ThumbnailCodec.TARGET_SIZE
                    + "×"
                    + ThumbnailCodec.TARGET_SIZE);
    JSONArray details = new JSONArray();
    int total = 0;
    String where = "readable=1 AND NOT ((width=? OR width=?) AND width=height)";
    String[] args = {
      Integer.toString(ThumbnailCodec.LEGACY_SIZE), Integer.toString(ThumbnailCodec.TARGET_SIZE)
    };
    try (Cursor c =
        q.getReadableDatabase()
            .rawQuery("SELECT COUNT(*) FROM fetch_images WHERE " + where, args)) {
      if (c.moveToFirst()) total = c.getInt(0);
    }
    if (offset >= 0)
      try (Cursor c =
          q.getReadableDatabase()
              .rawQuery(
                  "SELECT name,width,height FROM fetch_images WHERE "
                      + where
                      + " ORDER BY username LIMIT 100 OFFSET "
                      + Math.max(0, offset),
                  args)) {
        while (c.moveToNext())
          details.put(
              new JSONObject()
                  .put("name", c.getString(0))
                  .put("width", c.getInt(1))
                  .put("height", c.getInt(2)));
      }
    return out.put("count", total)
        .put("items", details)
        .put("offset", offset)
        .put("lastIndex", q.meta("lastIndex"))
        .put("verificationRecommended", "1".equals(q.meta("verificationRecommended")))
        .put("listings", listings.get())
        .put("opens", opens.get())
        .put("decodes", decodes.get())
        .put("hits", hits.get());
  }

  static void observe(Context c, Uri uri) {
    if (uri == null) return;
    Long own = ownUris.get(uri.toString());
    if (own != null) {
      if (own > System.currentTimeMillis()) return;
      ownUris.remove(uri.toString(), own);
    }
    try (QueueStore q = new QueueStore(c)) {
      boolean known = false;
      try (Cursor cursor =
          q.getReadableDatabase()
              .rawQuery(
                  "SELECT username FROM fetch_images WHERE uri=?", new String[] {uri.toString()})) {
        while (cursor.moveToNext()) {
          known = true;
          if (writing.contains(cursor.getString(0))) return;
        }
      }
      if (known) q.invalidateUri(uri.toString());
      else if (writing.isEmpty()) q.meta("verificationRecommended", "1");
      if (known || writing.isEmpty()) {
        Fetcher.host().sourceChanged();
        Fetcher.publish();
      }
    } catch (RuntimeException ignored) {
      new Diagnostics(c).log("INDEX", "CHANGE_REQUIRES_VERIFICATION", 0);
    }
  }

  private FetcherCatalog() {}
}
