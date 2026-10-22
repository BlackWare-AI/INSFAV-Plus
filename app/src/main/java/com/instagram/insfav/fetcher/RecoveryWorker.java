package com.instagram.insfav.fetcher;

import android.content.Context;
import androidx.work.*;

public final class RecoveryWorker extends Worker {
    public RecoveryWorker(Context c, WorkerParameters p) { super(c, p); }
    @Override public Result doWork() { if (WorkScheduler.enabled(SessionState.prefs(getApplicationContext())) && !QueueControl.lock.get()) WorkScheduler.plan(getApplicationContext(), false); return Result.success(); }
}
