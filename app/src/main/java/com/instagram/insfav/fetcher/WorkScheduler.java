package com.instagram.insfav.fetcher;

import android.content.*;
import androidx.work.*;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

final class WorkScheduler {
  static final String NEXT = "insfav-next-profile", RECOVERY = "insfav-queue-recovery";

  static boolean enabled(SharedPreferences p) {
    return p.getBoolean("background", false)
        && p.getBoolean("armed", false)
        && !p.getBoolean("userPaused", true)
        && (p.getString("blocked", "").isEmpty()
            || p.getString("blocked", "").equals("RATE_LIMITED"));
  }

  static void recovery(Context c) {
    if (!enabled(SessionState.prefs(c))) return;
    WorkManager.getInstance(c)
        .enqueueUniquePeriodicWork(
            RECOVERY,
            ExistingPeriodicWorkPolicy.KEEP,
            new PeriodicWorkRequest.Builder(RecoveryWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(
                    new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build());
  }

  static void plan(Context c, boolean append) {
    SharedPreferences p = SessionState.prefs(c);
    if (!enabled(p)) {
      try {
        WorkManager.getInstance(c).cancelUniqueWork(RECOVERY);
      } catch (RuntimeException ignored) {
      }
      return;
    }
    if (!new CookieVault(c).configured()) return;
    try (QueueStore queue = new QueueStore(c)) {
      if (queue.pending().isEmpty()) {
        p.edit().putBoolean("armed", false).apply();
        cancel(c);
        return;
      }
    }
    long now = System.currentTimeMillis();
    long earliest =
        Math.max(
            now,
            Math.max(
                p.getLong("nextRequestAt", 0),
                Math.max(p.getLong("retryAt", 0), p.getLong("systemNotBefore", 0))));
    earliest =
        Math.max(
            earliest,
            WorkerPacing.earliest(
                p, ConcurrencyPolicy.initial(c, p.getInt("concurrentWebViews", 1))));
    long next = RunPolicy.read(p).nextAllowed(earliest, ZoneId.systemDefault());
    OneTimeWorkRequest request =
        new OneTimeWorkRequest.Builder(DownloadWorker.class)
            .setInitialDelay(Math.max(0, next - now), TimeUnit.MILLISECONDS)
            .setConstraints(
                new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build();
    WorkManager.getInstance(c)
        .enqueueUniqueWork(
            NEXT, append ? ExistingWorkPolicy.APPEND_OR_REPLACE : ExistingWorkPolicy.KEEP, request);
    p.edit().putLong("scheduledAt", next).apply();
  }

  static void cancel(Context c) {
    try {
      WorkManager wm = WorkManager.getInstance(c);
      wm.cancelUniqueWork(NEXT);
      wm.cancelUniqueWork(RECOVERY);
    } catch (RuntimeException ignored) {
    }
  }
}
