package com.instagram.insfav.fetcher;

import android.content.*;
import java.util.concurrent.atomic.AtomicBoolean;

// Queue, session, schedule and recovery decisions must be durable before continuing.
@android.annotation.SuppressLint("ApplySharedPref")
final class QueueControl {
 static volatile int queuedAccounts, runProcessed;
 static volatile String lastResult="";
    static final AtomicBoolean lock = new AtomicBoolean();
    static volatile boolean requested, memoryPressure;
    static volatile int activeViews, effectiveViews = 1, activeAccounts, peakAccounts;
    static volatile double completedPerMinute;
    static volatile String requestedState = "Paused", current = "", phase = "Idle";
    static volatile long etaSeconds = -1;
    static void pause(Context context, boolean stop) {
        requestedState = stop ? "Stopped" : "Paused"; requested = true;
        SharedPreferences.Editor e = SessionState.prefs(context).edit().putBoolean("userPaused", true).putString("state", requestedState);
        if (stop) e.putBoolean("armed", false); e.commit(); WorkScheduler.cancel(context); FetchBroker.get(context).cancelBulk(); Fetcher.publish(true);
        if(!lock.get())DownloadNotification.publish(context,3,requestedState);
        new Diagnostics(context).log("QUEUE", stop ? "STOP" : "PAUSE", 0);
    }
}
