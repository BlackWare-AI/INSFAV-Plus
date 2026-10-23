package com.instagram.insfav.fetcher;

import android.content.*;

/** WorkManager persists across reboot; wall-clock changes must recalculate the next window. */
public final class ScheduleReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent) {
    String a = intent.getAction();
    if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
        && !Intent.ACTION_TIME_CHANGED.equals(a)
        && !Intent.ACTION_TIMEZONE_CHANGED.equals(a)) return;
    PendingResult pending = goAsync();
    new Thread(
            () -> {
              try {
                WorkScheduler.cancel(c);
                WorkScheduler.recovery(c);
                WorkScheduler.plan(c, false);
              } finally {
                pending.finish();
              }
            },
            "schedule-replan")
        .start();
  }
}
