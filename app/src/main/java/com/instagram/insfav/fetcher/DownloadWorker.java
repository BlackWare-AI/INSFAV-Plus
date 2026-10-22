package com.instagram.insfav.fetcher;

import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.work.*;

/** Bounded two-minute pooled recovery job; never a sleep loop or an exact alarm. */
// Queue, session, schedule and recovery decisions must be durable before continuing.
@android.annotation.SuppressLint("ApplySharedPref")
public final class DownloadWorker extends Worker {
    private volatile QueueEngine engine;
    public DownloadWorker(Context c, WorkerParameters p) { super(c, p); }
    @Override public Result doWork() {
        Context c = getApplicationContext();
        if (!WorkScheduler.enabled(SessionState.prefs(c))) return Result.success();
        if (!QueueControl.lock.compareAndSet(false, true)) return Result.success();
        QueueControl.requested = false;
        boolean foregroundReady = false;
        try {
            c.getSystemService(android.app.NotificationManager.class).cancel(3);
            setForegroundAsync(getForegroundInfo()).get();
            foregroundReady = true;
            engine = new QueueEngine(c, state -> { if (!isStopped()) DownloadNotification.publish(c,2,state); }, this::isStopped);
            engine.run("scheduled", true);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            SessionState.prefs(c).edit().putString("state", "Interrupted; queue preserved").commit();
            return Result.retry();
        } catch (Exception error) {
            String code = failureCode(error, !foregroundReady);
            SessionState.prefs(c).edit().putString("blocked", code).putString("state", code.equals("SYSTEM_RESTRICTED") ? "Paused: Android background restriction; open the app to resume" : "Paused: worker error; queue preserved").commit();
            new Diagnostics(c).log("SERVICE", code, 0);
        } finally {
            QueueControl.current = ""; QueueControl.lock.set(false); DownloadNotification.finish(c);
            WorkScheduler.plan(c, true);
        }
        return isStopped() ? Result.retry() : Result.success();
    }
    static String failureCode(Throwable error, boolean foreground) {
        if (error instanceof java.util.concurrent.ExecutionException && error.getCause() != null) return failureCode(error.getCause(), foreground);
        if (error instanceof android.database.sqlite.SQLiteException) return "DATABASE_ERROR";
        if (foreground && (error instanceof IllegalStateException || error instanceof SecurityException)) return "SYSTEM_RESTRICTED";
        if (error instanceof SecurityException) return "STORAGE_PERMISSION_LOST";
        return foreground ? "FOREGROUND_START_FAILED" : "WORKER_PROCESSING_ERROR";
    }
    @Override public ForegroundInfo getForegroundInfo() {
        return Build.VERSION.SDK_INT >= 29 ? new ForegroundInfo(2, DownloadNotification.preparing(getApplicationContext()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) : new ForegroundInfo(2, DownloadNotification.preparing(getApplicationContext()));
    }
    @Override public void onStopped() { QueueEngine current = engine; if (current != null) current.cancel(); }
}
