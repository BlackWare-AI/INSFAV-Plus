package com.instagram.insfav.fetcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.*;

/** One snapshot writer; provider lifetime never controls cookie extraction or persistence. */
final class BrowserCookieStore {
    interface Completion { void done(CookieJar jar, String error); }
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "instagram-cookie-store"); t.setDaemon(true); return t;
    });
    private static final java.util.Set<String> pending = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> dirty = ConcurrentHashMap.newKeySet();
    static CookieVault vault(Context c, String engine) {
        return engine.equals("netscape") ? NetscapeBrowserCookies.vault(c) : CookieVault.browser(c);
    }
    static String revision(Context c, String engine) {
        return SessionState.prefs(c).getLong("browserCookieEpoch",0) + ":" + SessionState.prefs(c).getLong("cookieSnapshotEpoch_"+engine,0);
    }
    static boolean removed(Context c, String engine) { return SessionState.prefs(c).getBoolean("cookieSnapshotRemoved_"+engine,false); }
    static boolean save(Context c, String engine, CookieJar jar, String epoch, boolean explicit, CookieVault vault) throws ProfileProvider.Failure {
        synchronized (BrowserCookies.LOCK) {
            if (!revision(c,engine).equals(epoch)) throw new ProfileProvider.Failure("REQUEST_CANCELLED");
            if (removed(c,engine) && !explicit) return false;
            try {
                String encoded = jar.encode(), old = "";
                try { if (vault.configured()) old = vault.stored(); } catch (Exception ignored) { }
                boolean changed = !encoded.equals(old);
                if (changed) { vault.save(jar); if (!encoded.equals(vault.stored())) throw new Exception(); }
                SessionState.prefs(c).edit().putBoolean("cookieSnapshotRemoved_"+engine,false)
                    .putLong("cookieSnapshotSavedAt_"+engine,System.currentTimeMillis()).putString("cookieSnapshotError_"+engine,"").commit();
                return changed;
            } catch (Exception e) { throw new ProfileProvider.Failure("COOKIE_STORAGE_FAILED"); }
        }
    }
    static CookieJar captureNow(Context c, String engine) throws ProfileProvider.Failure {
        String epoch = revision(c,engine);
        CookieJar jar = engine.equals("netscape") ? NetscapeBrowserCookies.read(c) : BrowserCookies.read(c);
        save(c,engine,jar,epoch,true,vault(c,engine)); return jar;
    }
    static void capture(Context source, String engine, boolean explicit, Completion completion) {
        Context c = source.getApplicationContext(); String epoch = revision(c,engine);
        String key = c.getPackageName()+":"+engine;
        if (!explicit) {
            if (removed(c,engine)) return;
            if (!pending.add(key)) { dirty.add(key); return; }
        }
        writer.execute(() -> {
            CookieJar jar = null; String error = "";
            try {
                if (!revision(c,engine).equals(epoch)) throw new ProfileProvider.Failure("REQUEST_CANCELLED");
                jar = engine.equals("netscape") ? NetscapeBrowserCookies.read(c) : BrowserCookies.read(c);
                save(c,engine,jar,epoch,explicit,vault(c,engine));
            } catch (ProfileProvider.Failure failure) {
                error = failure.code;
                if (revision(c,engine).equals(epoch) && !error.equals("REQUEST_CANCELLED")) SessionState.prefs(c).edit().putString("cookieSnapshotError_"+engine,error).commit();
            } finally { if (!explicit) { pending.remove(key); if (dirty.remove(key)) capture(c,engine,false,null); } }
            if (completion != null) {
                CookieJar result = jar; String failure = error;
                new Handler(Looper.getMainLooper()).post(() -> completion.done(revision(c,engine).equals(epoch) ? result : null, revision(c,engine).equals(epoch) ? failure : "REQUEST_CANCELLED"));
            }
        });
    }
    static void updated(Context c) {
        if (!InstagramSession.manual(c)) capture(c,InstagramSession.engine(c),false,null);
    }
    static void remove(Context c, String engine) {
        synchronized (BrowserCookies.LOCK) {
            SessionState.prefs(c).edit().putLong("cookieSnapshotEpoch_"+engine,SessionState.prefs(c).getLong("cookieSnapshotEpoch_"+engine,0)+1)
                .putBoolean("cookieSnapshotRemoved_"+engine,true).putLong("cookieSnapshotSavedAt_"+engine,0).putString("cookieSnapshotError_"+engine,"").commit();
            vault(c,engine).remove();
        }
    }
    static void saved(Context source, String engine, Completion completion) {
        Context c = source.getApplicationContext(); String epoch = revision(c,engine);
        writer.execute(() -> {
            CookieJar jar = null; String error = "";
            try { synchronized (BrowserCookies.LOCK) {
                if (!revision(c,engine).equals(epoch)) throw new ProfileProvider.Failure("REQUEST_CANCELLED");
                jar = vault(c,engine).load();
            } } catch (ProfileProvider.Failure e) { error = e.code; }
            CookieJar result = jar; String failure = error;
            new Handler(Looper.getMainLooper()).post(() -> completion.done(revision(c,engine).equals(epoch) ? result : null, revision(c,engine).equals(epoch) ? failure : "REQUEST_CANCELLED"));
        });
    }
    private BrowserCookieStore() { }
}
