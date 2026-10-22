package com.instagram.insfav.fetcher;

import android.content.*;
import java.util.*;

/** Only non-secret session generation and verification evidence are stored in preferences. */
final class SessionState {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("thumbnail-fetcher", Context.MODE_PRIVATE); }
    static void changed(Context c) {
        prefs(c).edit().putString("cookieGeneration", UUID.randomUUID().toString()).putString("sessionEvidence", "UNKNOWN").putBoolean("armed", false).putBoolean("userPaused", true).putString("blocked", "").commit();
        WorkScheduler.cancel(c);
    }
    static boolean invalid(ProfileProvider.Failure e) { return e.code.equals("COOKIE_INVALID_EXPIRED") || e.code.equals("COOKIE_EXPIRED"); }
    static String pausedText(String code) {
        return code.equals("COOKIE_INVALID_EXPIRED") || code.equals("COOKIE_EXPIRED") ? "Paused: session not verified" : "Paused: " + code;
    }
    static String evidence(SharedPreferences p) {
        String value = p.getString("sessionEvidence", "UNKNOWN");
        if (value.equals("INVALID")) return "UNVERIFIED";
        return value.equals("VERIFIED") || value.equals("UNVERIFIED") ? value : "UNKNOWN";
    }
}
