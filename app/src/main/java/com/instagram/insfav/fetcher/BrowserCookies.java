package com.instagram.insfav.fetcher;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.webkit.CookieManager;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** CookieManager owns values/deletions. Chromium is optional, read-only metadata only. */
final class BrowserCookies {
    static final Object LOCK = new Object();
    static CookieJar read(Context context) throws ProfileProvider.Failure {
        try {
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            FutureTask<String> task = new FutureTask<>(() -> {
                CookieManager manager = CookieManager.getInstance(); manager.flush();
                return manager.getCookie(InstagramSession.ORIGIN);
            });
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) task.run(); else main.post(task);
            long now = System.currentTimeMillis();
            CookieJar current = CookieJar.parse(task.get(10, TimeUnit.SECONDS), now);
            Map<String, CookieJar.Entry> metadata = new HashMap<>();
            ArrayList<CookieJar.Entry> scoped = new ArrayList<>();
            File base = new File(context.getApplicationInfo().dataDir, "app_webview/Default");
            File file = new File(base, "Cookies");
            if (!file.isFile()) file = new File(base, "Network/Cookies");
            // Unsupported/missing/locked Chromium schemas never make a live API cookie unusable.
            if (file.isFile()) try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor rows = db.query("cookies", new String[]{"host_key","path","name","value","expires_utc"},
                    "host_key IN (?,?,?,?)", new String[]{"instagram.com",".instagram.com","www.instagram.com",".www.instagram.com"}, null,null,null)) {
                while (rows.moveToNext()) {
                    String host = rows.getString(0), path = rows.getString(1), name = rows.getString(2), value = rows.getString(3);
                    if (path == null || !path.matches("/[A-Za-z0-9_./%~\\-]*")) continue;
                    CookieJar api = current;
                    if (!path.equals("/")) {
                        String url = "https://" + host.replaceFirst("^\\.", "") + path;
                        FutureTask<String> scopedTask = new FutureTask<>(() -> CookieManager.getInstance().getCookie(url));
                        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) scopedTask.run(); else main.post(scopedTask);
                        try { api = CookieJar.parse(scopedTask.get(10, TimeUnit.SECONDS), now); }
                        catch (ProfileProvider.Failure ignored) { continue; }
                    }
                    String live = api.value(name, now);
                    // Metadata cannot revive a deleted cookie, replace a fresh value or guess encrypted-row expiry.
                    if (live.isEmpty() || value == null || !live.equals(value)) continue;
                    long chrome = rows.getLong(4), expiry = chrome == 0 ? 0 : chrome / 1000000L - 11644473600L;
                    if (expiry < 0 || expiry != 0 && expiry <= now / 1000) continue;
                    String domain = host.replaceFirst("^\\.", "");
                    if (!domain.equals("www.instagram.com") && !host.startsWith(".")) continue;
                    CookieJar.Entry candidate = new CookieJar.Entry(name, live, domain, path, host.startsWith("."), expiry);
                    if (!path.equals("/")) { scoped.add(candidate); continue; }
                    CookieJar.Entry old = metadata.get(name);
                    if (old == null || candidate.domain.length() > old.domain.length()) metadata.put(name, candidate);
                }
            } catch (RuntimeException ignored) { /* CookieManager remains authoritative. */ }
            ArrayList<CookieJar.Entry> entries = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (CookieJar.Entry entry : current.entries) if (names.add(entry.name)) entries.add(metadata.getOrDefault(entry.name, entry));
            entries.addAll(scoped);
            return new CookieJar(entries);
        } catch (ProfileProvider.Failure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ProfileProvider.Failure("REQUEST_CANCELLED"); }
        catch (Exception e) { throw new ProfileProvider.Failure("COOKIE_EXTRACTION_FAILED"); }
    }
    static CookieJar capture(Context c) throws ProfileProvider.Failure { return BrowserCookieStore.captureNow(c, "legacy"); }
    static CookieJar forDownload(Context c) throws ProfileProvider.Failure { return read(c); }
    private BrowserCookies() { }
}
