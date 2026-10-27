package com.instagram.insfav.fetcher;

import android.content.*;

/** Durable bulk-only switch. Never clears browser storage or disables explicit HD requests. */
final class BulkControl {
    static boolean disabled(Context c) { return SessionState.prefs(c).getBoolean("bulkDisabled", false); }
    static void requireEnabled(Context c) throws java.io.IOException {
        if (disabled(c)) throw new java.io.IOException("Thumbnail Fetcher is disabled. Enable it before starting bulk retrieval.");
    }
    private BulkControl() { }
}
