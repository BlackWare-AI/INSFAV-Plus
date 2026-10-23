package com.instagram.insfav;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.*;

/** Read-only, paginated inventory. Opaque, scoped preview tokens never authorize writes. */
final class ThumbnailInventory implements AutoCloseable {
  private final Context context;
  private final Store store;
  private final LocalImages images;
  private final Map<String, Preview> previews = new ConcurrentHashMap<>();
  private final Set<InputStream> streams = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;
  private static final int PAGE = 100;

  private static final class Preview {
    final Uri uri;
    final String scope;

    Preview(String uri, String scope) {
      this.uri = Uri.parse(uri);
      this.scope = scope;
    }
  }

  ThumbnailInventory(Context context, Store store, LocalImages images) {
    this.context = context.getApplicationContext();
    this.store = store;
    this.images = images;
  }

  JSONObject query(String kind, int offset, String search) throws Exception {
    if (!Arrays.asList("missing", "present", "orphan").contains(kind))
      throw new IOException("Unsupported inventory view");
    images.checkAccess();
    previews.clear();
    int start = Math.max(0, offset), total = 0;
    JSONArray items = new JSONArray();
    String scope = images.scopeRevision(), needle = search.toLowerCase(Locale.ROOT);
    synchronized (store) {
      boolean available =
          "ready".equals(images.snapshot(false).optString("folderStatus"))
              && store
                  .settings
                  .optString("treeUri")
                  .equals(store.index.optString("treeUri", "invalid"));
      Set<String> users = store.users();
      JSONObject profiles = store.index.optJSONObject("profiles");
      if (!kind.equals("orphan")) {
        for (String user : users) {
          JSONObject image = profiles == null ? null : profiles.optJSONObject(user);
          boolean present = available && image != null && image.optBoolean("readable");
          if (present != kind.equals("present") || !user.contains(needle)) continue;
          if (total >= start && items.length() < PAGE)
            items.put(item(user, user, image, present, scope));
          total++;
        }
        return result(kind, start, total, items, true);
      }
      if (!available || images.scanning() || !new File(store.root, "thumbnail-scan.db").isFile())
        return result(kind, start, 0, items, false);
      String folder =
          store.settings.optString("treeUri") + "\n" + store.settings.optString("directoryId");
      try (ThumbnailScanIndex index = new ThumbnailScanIndex(context, store.root);
          Cursor cursor =
              index
                  .getReadableDatabase()
                  .rawQuery(
                      "SELECT name,uri,width,height,readable FROM entries JOIN state ON state.id=1"
                          + " WHERE state.scope=? AND state.completed=1 ORDER BY name,document_id",
                      new String[] {folder})) {
        boolean complete = index.resolution(folder) != null;
        if (!complete) return result(kind, start, 0, items, false);
        while (cursor.moveToNext()) {
          String name = cursor.getString(0), user = ThumbnailPolicy.username(name);
          if (users.contains(user) || !name.toLowerCase(Locale.ROOT).contains(needle)) continue;
          if (total >= start && items.length() < PAGE) {
            JSONObject image =
                new JSONObject()
                    .put("uri", cursor.getString(1))
                    .put("width", cursor.getInt(2))
                    .put("height", cursor.getInt(3));
            items.put(item(name, user, image, cursor.getInt(4) != 0, scope));
          }
          total++;
        }
      }
    }
    return result(kind, start, total, items, true);
  }

  private JSONObject result(String kind, int offset, int total, JSONArray items, boolean complete)
      throws JSONException {
    return new JSONObject()
        .put("kind", kind)
        .put("offset", offset)
        .put("total", total)
        .put("pageSize", PAGE)
        .put("items", items)
        .put("complete", complete)
        .put("readOnly", true);
  }

  private JSONObject item(
      String name, String user, JSONObject image, boolean readable, String scope)
      throws JSONException {
    JSONObject item =
        new JSONObject()
            .put("name", name)
            .put("user", user == null ? "" : user)
            .put("readable", readable);
    if (readable && image != null) {
      String token = UUID.randomUUID().toString();
      previews.put(token, new Preview(image.getString("uri"), scope));
      item.put("preview", token)
          .put("width", image.optInt("width"))
          .put("height", image.optInt("height"));
    }
    return item;
  }

  InputStream open(String token) throws IOException {
    Preview preview = previews.get(token);
    images.checkAccess();
    if (closed || preview == null || !preview.scope.equals(images.scopeRevision()))
      throw new IOException("Preview expired");
    InputStream source = ImageCodec.open(context.getContentResolver(), preview.uri, null);
    InputStream stream =
        new FilterInputStream(source) {
          long read;
          boolean done;

          private void count(int n) throws IOException {
            if (n > 0 && (read += n) > 64L * 1024 * 1024) {
              close();
              throw new IOException("Preview size limit");
            }
          }

          @Override
          public int read() throws IOException {
            int n = super.read();
            count(n < 0 ? 0 : 1);
            return n;
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            count(n);
            return n;
          }

          @Override
          public void close() throws IOException {
            if (done) return;
            done = true;
            try {
              super.close();
            } finally {
              streams.remove(this);
            }
          }
        };
    streams.add(stream);
    if (closed) {
      stream.close();
      throw new IOException("Preview closed");
    }
    return stream;
  }

  @Override
  public void close() {
    closed = true;
    previews.clear();
    for (InputStream stream : streams)
      try {
        stream.close();
      } catch (IOException ignored) {
      }
    streams.clear();
  }
}
