package com.instagram.insfav.fetcher;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Fixed vocabulary and numeric details only. No raw URLs, usernames, exceptions, or headers. */
final class Diagnostics {
    private static final Object FILE_LOCK = new Object();
    private final File current, previous;
    private final Context context;
    Diagnostics(Context context) { this.context = context.getApplicationContext(); current = new File(context.getFilesDir(), "fetcher-diagnostics.txt"); previous = new File(context.getFilesDir(), "fetcher-diagnostics.previous.txt"); }
    void log(String event, String code, long detail) { synchronized(FILE_LOCK){
        if (!event.matches("[A-Z_]+") || !code.matches("[A-Z_0-9]+")) return;
        try {
            if (current.length() > 128 * 1024) { if (previous.exists() && !previous.delete()) return; if (!current.renameTo(previous)) return; }
            try (FileOutputStream output = new FileOutputStream(current, true)) {
                output.write(String.format(Locale.ROOT, "%d %s %s %d%n", System.currentTimeMillis(), event, code, detail).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { }
    }
    }
    enum Reason { TEXT_MATCH, ROUTED_ERROR, CANONICAL_MISMATCH, NO_CANDIDATE, DEADLINE, COOKIE_OR_LOGIN_GATE, PAGE_NOT_READY, UNKNOWN_RESPONSE }
    static Reason reason(String value) {
        try { return Reason.valueOf(value); } catch (IllegalArgumentException | NullPointerException ignored) { return Reason.UNKNOWN_RESPONSE; }
    }
    void outcome(String code, Reason reason, int http, boolean pageFinished, boolean candidate,
                 String session, long elapsedMs, long timeoutMs) {
        // All text fields originate in fixed vocabularies. Never accept probe strings as free text.
        if (!code.matches("[A-Z_0-9]+")) return;
        String evidence = "VERIFIED".equals(session) ? "VERIFIED" : "UNVERIFIED".equals(session) ? "UNVERIFIED" : "UNKNOWN";
        android.content.SharedPreferences p = SessionState.prefs(context);
        log("RESULT", code, Math.max(0, http));
        synchronized (FILE_LOCK) {
            try (FileOutputStream out = new FileOutputStream(current, true)) {
                String line = String.format(Locale.ROOT,
                    "RESULT_DETAIL code=%s reason=%s http=%d pageFinished=%s candidateFound=%s session=%s elapsedMs=%d timeoutMs=%d workers=%d effectiveWorkers=%d activeAccounts=%d queuedAccounts=%d queue=%s batch=%d%n",
                    code, reason.name(), http >= 100 && http <= 599 ? http : 0, pageFinished, candidate, evidence,
                    Math.max(0, elapsedMs), Math.max(0, timeoutMs), p.getInt("concurrentWebViews", 1), QueueControl.effectiveViews,
                    QueueControl.activeAccounts, Math.max(0, QueueControl.queuedAccounts),
                    !p.getString("blocked", "").isEmpty() ? "PAUSED" : QueueControl.activeAccounts > 0 ? "ACTIVE" : "IDLE", p.getLong("diagnosticBatch", 0));
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
    }
    void session(String code) {
        for (String allowed : new String[]{"SHARED_COOKIES_APPLIED", "SHARED_COOKIES_REUSED", "AUTHENTICATION_STARTED", "SESSION_VERIFIED", "SESSION_UNVERIFIED", "SESSION_UNKNOWN"})
            if (allowed.equals(code)) { log("SESSION", allowed, SessionState.prefs(context).getLong("diagnosticBatch", 0)); return; }
    }
    static String storageCode(IOException error) {
        String code = error.getMessage();
        for (String allowed : new String[]{"OUTPUT_PERMISSION_LOST", "OUTPUT_SELECT_PARENT", "OUTPUT_FOLDER_CREATE_FAILED", "OUTPUT_FOLDER_INVALID", "OUTPUT_UNAVAILABLE", "OUTPUT_DUPLICATE_NAMES", "OUTPUT_READ_FAILED", "OUTPUT_SIZE_REJECTED", "PARTIAL_CLEANUP_FAILED", "OUTPUT_INVALID_WEBP", "OUTPUT_CREATE_FAILED", "OUTPUT_WRITE_FAILED", "OUTPUT_READBACK_FAILED", "OUTPUT_REPLACE_FAILED", "ATOMIC_RENAME_UNSUPPORTED", "OUTPUT_FINAL_VERIFY_FAILED", "OUTPUT_FILENAME_CHANGED"}) if (allowed.equals(code)) return allowed;
        return "IO_ERROR";
    }
    static String imageCode(IOException error) {
        String code = error.getMessage();
        for (String allowed : new String[]{"IMAGE_SIZE_REJECTED", "IMAGE_CONTAINER_INVALID", "IMAGE_REJECTED", "IMAGE_DECODE_FAILED", "WEBP_ENCODE_FAILED", "WEBP_VERIFY_FAILED"}) if (allowed.equals(code)) return allowed;
        return "IMAGE_VALIDATION_FAILED";
    }
    static String importCode(Throwable error) {
        String code = error.getMessage();
        for (String allowed : new String[]{"JSON_TOO_LARGE", "JSON_TOO_DEEP", "JSON_OPEN_FAILED", "DUPLICATE_BACKUP_FIELD", "TRAILING_JSON", "BACKUP_FIELD_MISSING", "INVALID_BACKUP_ROOT", "INVALID_JSON", "DUPLICATE_ITEMS", "COLLECTION_ITEMS_MISSING"}) if (allowed.equals(code)) return allowed;
        return error instanceof OutOfMemoryError ? "IMPORT_MEMORY_LIMIT" : "INVALID_OR_UNREADABLE_JSON";
    }
    void export(OutputStream output) throws IOException { synchronized(FILE_LOCK){
        output.write("INSFAV integrated WebView fetcher\nEngine: hidden Android WebView + trusted HTTPS image download.\nAccount identifiers and cookies redacted. No secret values exported.\n".getBytes(StandardCharsets.UTF_8));
        try (QueueStore queue = new QueueStore(context)) {
            output.write(("Requested WebViews: " + SessionState.prefs(context).getInt("concurrentWebViews",1) + "\nEffective WebViews: " + QueueControl.effectiveViews + "\nActive WebViews: " + QueueControl.activeViews + "\nActive Accounts: " + QueueControl.activeAccounts + "\nPeak Active Accounts: " + QueueControl.peakAccounts + "\nCompleted Per Minute: " + QueueControl.completedPerMinute + "\nApp PSS KiB: " + android.os.Debug.getPss() + "\nAndroid SDK: " + android.os.Build.VERSION.SDK_INT + "\nFetcher phases: "+queue.phases()+"\nFetcher full listings: "+FetcherCatalog.listings.get()+"\nFetcher source opens: "+FetcherCatalog.opens.get()+"\nFetcher bitmap decodes: "+FetcherCatalog.decodes.get()+"\nFetcher metadata hits: "+FetcherCatalog.hits.get()+"\nSaved profile outcomes (private ledger): "+queue.recordedOutcomes()+"\nDeferred profiles awaiting explicit Retry: "+queue.held()+"\nCounters: " + queue.counts() + "\nFailures: " + queue.failures() + "\nSession evidence (UNKNOWN means no verification evidence): " + SessionState.evidence(SessionState.prefs(context)) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        for (File file : new File[]{previous, current}) if (file.exists()) try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[4096]; int n; while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        }
    }}
}
