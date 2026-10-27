package com.instagram.insfav.fetcher;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.webkit.CookieManager;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Own-app default WebView only. Read-only Chromium metadata, supported CookieManager values. */
final class BrowserCookies {
    static final Object LOCK=new Object();
    static CookieJar read(Context context) throws ProfileProvider.Failure {
        try {
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            FutureTask<String> task = new FutureTask<>(() -> {
                CookieManager manager = CookieManager.getInstance(); manager.flush();
                return manager.getCookie(InstagramSession.ORIGIN);
            });
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) task.run(); else main.post(task);
            String header = task.get(10, TimeUnit.SECONDS);
            CookieJar api = CookieJar.parse(header, System.currentTimeMillis());
            File base = new File(context.getApplicationInfo().dataDir, "app_webview/Default");
            File db = new File(base,"Cookies"); if (!db.isFile()) db = new File(base,"Network/Cookies");
            if (!db.isFile()) return api;
            ArrayList<CookieJar.Entry> values = new ArrayList<>();
            try (SQLiteDatabase sql=SQLiteDatabase.openDatabase(db.getPath(),null,SQLiteDatabase.OPEN_READONLY);
                 Cursor rows=sql.query("cookies",new String[]{"host_key","path","name","value","expires_utc"},
                     "host_key IN (?,?,?,?)",new String[]{"instagram.com",".instagram.com","www.instagram.com",".www.instagram.com"},null,null,null)) {
                while(rows.moveToNext()) {
                    String host=rows.getString(0),path=rows.getString(1),name=rows.getString(2),value=rows.getString(3);
                    long chrome=rows.getLong(4), expiry=chrome==0?0:chrome/1000000L-11644473600L;
                    if (expiry<0 || expiry!=0 && expiry<=System.currentTimeMillis()/1000) continue;
                    if("/".equals(path)||value==null||value.isEmpty()) value=api.value(name,System.currentTimeMillis());
                    if(value.isEmpty()) continue; // OS-encrypted values must come from CookieManager, never decrypt Chromium storage.
                    CookieJar.parse((name.equals("sessionid")?"":"sessionid=VALIDATION_ONLY; ")+name+"="+value,System.currentTimeMillis());
                    values.add(new CookieJar.Entry(name,value,host.replaceFirst("^\\.",""),path,host.startsWith("."),expiry));
                }
            } catch (RuntimeException ignored) { return api; }
            // CookieManager is authoritative for current root-path values and deletions.
            if(values.isEmpty()) return api;
            Set<String> currentNames=new HashSet<>();for(CookieJar.Entry e:api.entries)currentNames.add(e.name);
            values.removeIf(e->"/".equals(e.path)&&!currentNames.contains(e.name));
            for(CookieJar.Entry entry:api.entries)if(values.stream().noneMatch(e->e.name.equals(entry.name)&&"/".equals(e.path)))values.add(entry);
            return new CookieJar(values);
        } catch (ProfileProvider.Failure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ProfileProvider.Failure("REQUEST_CANCELLED"); }
        catch (Exception e) { throw new ProfileProvider.Failure("COOKIE_EXTRACTION_FAILED"); }
    }
    static CookieJar capture(Context c) throws ProfileProvider.Failure {
        long epoch=SessionState.prefs(c).getLong("browserCookieEpoch",0);
        CookieJar jar=read(c);
        try { synchronized(LOCK) {
            if(epoch!=SessionState.prefs(c).getLong("browserCookieEpoch",0))throw new ProfileProvider.Failure("REQUEST_CANCELLED");
            CookieVault.browser(c).save(jar);
        } }
        catch (Exception e) { throw new ProfileProvider.Failure("COOKIE_STORAGE_FAILED"); }
        return jar;
    }
    static CookieJar forDownload(Context c) throws ProfileProvider.Failure {
        try { return capture(c); }
        catch(ProfileProvider.Failure failure) {
            if(failure.code.equals("COOKIE_STORAGE_FAILED"))return read(c);
            if(!CookieVault.browser(c).configured())throw failure;
            return CookieVault.browser(c).load();
        }
    }
    private BrowserCookies() { }
}
