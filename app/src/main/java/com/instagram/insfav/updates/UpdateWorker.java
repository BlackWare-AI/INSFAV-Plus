package com.instagram.insfav.updates;
import android.content.Context;
import androidx.work.*;
/** WorkManager survives process death and reboot; no always-running service. */
public final class UpdateWorker extends Worker {
    public UpdateWorker(Context c,WorkerParameters p){super(c,p);}
    @Override public Result doWork(){Context c=getApplicationContext();if(!AppUpdates.prefs(c).getBoolean("check",false))return Result.success();try{AppUpdates.check(c);if(isStopped())return Result.success();if(AppUpdates.prefs(c).getBoolean("download",false)&&AppUpdates.prefs(c).getBoolean("available",false)&&!AppUpdates.prefs(c).getBoolean("ready",false))AppUpdates.download(c,true);return Result.success();}catch(Exception e){return Result.failure();}}
}
