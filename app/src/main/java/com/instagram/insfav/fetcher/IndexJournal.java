package com.instagram.insfav.fetcher;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONObject;

/** Durable public-index deltas in the existing key/value metadata table; no schema migration. */
public final class IndexJournal implements AutoCloseable {
    private static final String PREFIX = "plus-index-delta:";
    private final QueueStore queue;
    public IndexJournal(Context context) { queue = new QueueStore(context); }
    public void record(String scope, String user, JSONObject profile, long revision) throws Exception {
        if (!Identity.valid(user)) throw new IllegalArgumentException("INVALID_ACCOUNT_KEY");
        JSONObject delta = new JSONObject().put("scope",scope).put("user",user).put("profile",profile).put("revision",revision);
        queue.meta(PREFIX + user, delta.toString());
    }
    public List<JSONObject> pending(String scope) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor cursor = queue.getReadableDatabase().rawQuery("SELECT value FROM fetch_meta WHERE name LIKE ?", new String[]{PREFIX+"%"})) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject(cursor.getString(0));
                if (scope.equals(item.getString("scope"))) result.add(item);
            }
        }
        result.sort(Comparator.comparingLong(item -> item.optLong("revision")));
        return result;
    }
    public void acknowledge(String scope, long revision) throws Exception {
        SQLiteDatabase db = queue.getWritableDatabase(); db.beginTransaction();
        try {
            List<String> names = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT name,value FROM fetch_meta WHERE name LIKE ?",new String[]{PREFIX+"%"})) {
                while (cursor.moveToNext()) {
                    JSONObject item = new JSONObject(cursor.getString(1));
                    if (scope.equals(item.getString("scope")) && item.getLong("revision") <= revision) names.add(cursor.getString(0));
                }
            }
            for (String name : names) db.delete("fetch_meta", "name=?",new String[]{name});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public void clear() { queue.getWritableDatabase().delete("fetch_meta","name LIKE ?",new String[]{PREFIX+"%"}); }
    @Override public void close() { queue.close(); }
}
