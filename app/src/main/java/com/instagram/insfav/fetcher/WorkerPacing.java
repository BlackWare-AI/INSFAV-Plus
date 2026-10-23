package com.instagram.insfav.fetcher;

import android.content.SharedPreferences;

/** Persistent start-to-start spacing per worker. Server cooldown remains pool-wide. */
// Queue, session, schedule and recovery decisions must be durable before continuing.
@android.annotation.SuppressLint("ApplySharedPref")
final class WorkerPacing {
  static long ready(SharedPreferences p, int slot) {
    return p.getLong("workerNextAt_" + slot, 0);
  }

  static void reserve(SharedPreferences p, int slot, long now, RunPolicy policy) {
    p.edit().putLong("workerNextAt_" + slot, now + policy.delayMillis()).commit();
  }

  static long earliest(SharedPreferences p, int limit) {
    long next = Long.MAX_VALUE;
    for (int i = 0; i < limit; i++) next = Math.min(next, ready(p, i));
    return next == Long.MAX_VALUE ? 0 : next;
  }

  private WorkerPacing() {}
}
