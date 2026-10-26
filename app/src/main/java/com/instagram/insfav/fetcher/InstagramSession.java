package com.instagram.insfav.fetcher;

import android.content.*;
import android.webkit.*;
import androidx.webkit.*;
import java.util.*;

/** WebView owns browser secrets; preferences contain only method/status and a durable safety latch. */
public final class InstagramSession {
    static final String ORIGIN = "https://www.instagram.com/";
    static final String MANUAL_PROFILE = "instagram-manual";
    private InstagramSession() { }
    public static boolean manual(Context c) { return SessionState.prefs(c).getBoolean("manualSession", false); }
    static String key(boolean manual) { return manual ? "manualSessionHold" : "webSessionHold"; }
    public static String status(Context c) {
        android.content.SharedPreferences p = SessionState.prefs(c);
        return (manual(c) ? "Manual cookies · " : "Persistent WebView Session · ") +
            ((p.getBoolean("instagramSecurityHold", false) || p.getBoolean(key(manual(c)), false)) ? "Verification required" : p.getString(manual(c) ? "manualSessionStatus" : "webSessionStatus", "Not verified"));
    }
    public static void select(Context c, boolean manual) {
        if (QueueControl.lock.get()) throw new IllegalStateException("Pause retrieval before changing the session method.");
        if (manual(c) == manual) return;
        SessionState.prefs(c).edit().putBoolean("manualSession", manual).commit();
        SessionState.changed(c);
    }
    static boolean configured(Context c) { return manual(c) ? new CookieVault(c).configured() : "Verified".equals(SessionState.prefs(c).getString("webSessionStatus", "")); }
    static WebViewProfileProvider open(Context c) throws ProfileProvider.Failure {
        requireReady(c);
        return new WebViewProfileProvider(c, manual(c) ? new CookieVault(c).load() : null);
    }
    static void requireReady(Context c) throws ProfileProvider.Failure {
        if (SessionState.prefs(c).getBoolean("instagramSecurityHold", false) || SessionState.prefs(c).getBoolean(key(manual(c)), false))
            throw new ProfileProvider.Failure("LOGIN_VERIFICATION_REQUIRED", 0, 0, true);
    }
    static boolean security(String code) {
        return code.startsWith("COOKIE_") || code.startsWith("LOGIN_") || code.equals("CHALLENGE_REQUIRED") || code.equals("ACCESS_BLOCKED") || code.equals("PRIVATE_RESTRICTED");
    }
    static void blocked(Context c, boolean manual, String code) {
        if (!security(code)) return;
        SessionState.prefs(c).edit().putBoolean("instagramSecurityHold", true).putBoolean(key(manual), true)
            .putString(manual ? "manualSessionStatus" : "webSessionStatus", "Verification required")
            .putBoolean("armed", false).putBoolean("userPaused", true).putString("blocked", code).commit();
        WorkScheduler.cancel(c);
    }
    static void verified(Context c, boolean manual) {
        // Flush before recording success, including after a completed login/2FA flow.
        manager(manual).flush();
        recordVerified(c, manual);
    }
    static void recordVerified(Context c, boolean manual) {
        SessionState.prefs(c).edit().putBoolean("instagramSecurityHold", false).putBoolean(key(manual), false)
            .putString(manual ? "manualSessionStatus" : "webSessionStatus", "Verified")
            .putLong(manual ? "manualSessionCheckedAt" : "webSessionCheckedAt", System.currentTimeMillis()).putString("sessionEvidence", "VERIFIED").commit();
        // Verification never arms or resumes the download queue.
    }
    static boolean profiles() { try { return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE); } catch (RuntimeException | LinkageError ignored) { return false; } }
    static CookieManager manager(boolean manual) {
        if (!manual) return CookieManager.getInstance();
        if (!profiles()) throw new IllegalStateException("Update Android System WebView to use isolated manual cookies.");
        return ProfileStore.getInstance().getOrCreateProfile(MANUAL_PROFILE).getCookieManager();
    }
    static void attach(WebView view, boolean manual) {
        if (manual) {
            if (!profiles()) throw new IllegalStateException("Update Android System WebView to use isolated manual cookies.");
            WebViewCompat.setProfile(view, MANUAL_PROFILE);
        }
    }
    static String account(CookieManager manager) {
        String header = manager.getCookie(ORIGIN);
        if (header != null) for (String part : header.split(";")) {
            String value = part.trim();
            if (value.startsWith("ds_user_id=")) {
                String id = value.substring(11);
                if (id.matches("[1-9][0-9]*")) return id;
            }
        }
        return "";
    }
    static void removeManual() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                if (!profiles()) return;
                androidx.webkit.Profile profile = ProfileStore.getInstance().getOrCreateProfile(MANUAL_PROFILE);
                profile.getCookieManager().removeAllCookies(ignored -> profile.getCookieManager().flush());
                profile.getWebStorage().deleteAllData();
            } catch (RuntimeException | LinkageError ignored) { }
        });
    }
    public static void flush() {
        try { CookieManager.getInstance().flush(); } catch (RuntimeException | LinkageError ignored) { }
        try { if (profiles() && ProfileStore.getInstance().getAllProfileNames().contains(MANUAL_PROFILE)) ProfileStore.getInstance().getProfile(MANUAL_PROFILE).getCookieManager().flush(); }
        catch (RuntimeException | LinkageError ignored) { }
    }
    public static void show(android.app.Activity activity) {
        activity.startActivity(new Intent(activity, InstagramLoginActivity.class));
    }
    public static void test(android.app.Activity activity) {
        if (QueueControl.lock.get()) { android.widget.Toast.makeText(activity, "Pause retrieval before testing the session.", android.widget.Toast.LENGTH_LONG).show(); return; }
        final boolean testingManual = manual(activity);
        new Thread(() -> {
            String result;
            try (WebViewProfileProvider provider = new WebViewProfileProvider(activity, testingManual ? new CookieVault(activity).load() : null)) {
                provider.authenticate(); result = "Session verified. Resume retrieval manually.";
            } catch (ProfileProvider.Failure error) {
                SessionState.prefs(activity).edit().putString(testingManual ? "manualSessionStatus" : "webSessionStatus", "Not verified").commit();
                result = error.code.equals("COOKIE_WEBVIEW_UPDATE_REQUIRED") ? "Update Android System WebView to use isolated Manual Cookies." : "Session test: " + error.code; }
            final String message = result;
            activity.runOnUiThread(() -> android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show());
        }, "instagram-session-test").start();
    }
    static void logout(Context c, Runnable completed) {
        // The default browser is restricted to Instagram by the login Activity and fetcher.
        // Expire Instagram cookies only; preserve other origins, the manual profile and all app files.
        CookieManager m = manager(false);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            WebStorageCompat.deleteBrowsingDataForSite(WebStorage.getInstance(), "instagram.com", () -> {
                m.flush(); signedOut(c); completed.run();
            });
            return;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String origin : new String[]{ORIGIN, "https://instagram.com/", ORIGIN + "accounts/", ORIGIN + "accounts/login/"}) {
            String header = m.getCookie(origin);
            if (header != null) for (String part : header.split(";")) {
                int at = part.indexOf('='); if (at > 0) names.add(part.substring(0, at).trim());
            }
        }
        java.util.ArrayList<String> removals = new java.util.ArrayList<>();
        for (String name : names) if (name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
            for (String domain : new String[]{"", "; Domain=instagram.com", "; Domain=www.instagram.com"})
                for (String path : new String[]{"/", "/accounts", "/accounts/", "/accounts/login/"})
                    removals.add(name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=" + path + domain + "; Secure");
        Runnable finish = () -> {
            WebStorage.getInstance().deleteOrigin(ORIGIN.substring(0, ORIGIN.length() - 1));
            WebStorage.getInstance().deleteOrigin("https://instagram.com");
            m.flush(); signedOut(c); completed.run();
        };
        if (removals.isEmpty()) { finish.run(); return; }
        java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(removals.size());
        for (String cookie : removals) m.setCookie(ORIGIN, cookie, accepted -> { if (pending.decrementAndGet() == 0) finish.run(); });
    }
    private static void signedOut(Context c) {
        SessionState.prefs(c).edit().putString("webSessionStatus", "Signed out").putBoolean("webSessionHold", true).commit();
        SessionState.changed(c);
    }
}
