package com.instagram.insfav.fetcher;

import android.app.*;
import com.instagram.insfav.MainActivity;
import com.instagram.insfav.R;
import android.content.*;
import java.util.*;

/** Queue-backed progress. Browser workers never manufacture download successes. */
final class DownloadNotification {
    private static long lastAt;
    private static String lastPhase="";
    static void channel(Context c) { c.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("downloads", "Thumbnail downloads", NotificationManager.IMPORTANCE_LOW)); }
    static final class Progress {
        final int downloaded,existing,failed,skipped,pending,total,done,percent,held;
        final String status;
        Progress(Map<String,Integer> counts,String phase,String blocked) {this(counts,phase,blocked,0);}
        Progress(Map<String,Integer> counts,String phase,String blocked,int savedRetries) {
            held=Math.max(0,Math.min(savedRetries,counts.getOrDefault("RETRY_LATER",0)));
            downloaded=counts.getOrDefault("SUCCESS",0);existing=counts.getOrDefault("EXISTING",0);failed=counts.getOrDefault("FAILED",0);
            skipped=counts.getOrDefault("SKIPPED_UNAVAILABLE",0)+counts.getOrDefault("SKIPPED_NO_IMAGE",0);
            pending=counts.getOrDefault("PENDING",0)+counts.getOrDefault("RETRY_LATER",0)-held;
            total=downloaded+existing+failed+skipped+pending+held;done=total-pending;percent=total==0?0:(int)(100L*done/total);
            status=phase.equals("Paused")||phase.equals("Stopped")?"Paused":
                blocked.equals("RATE_LIMITED")?"Rate Limited":
                blocked.matches(".*(COOKIE|LOGIN|CHALLENGE|AUTH).*" )?"Authentication Required":
                !blocked.isEmpty()?"Failed":
                phase.startsWith("Starting")||phase.equals("Preparing")?"Starting":
                (phase.equals("Completed")||phase.startsWith("Finished:"))&&pending==0?(held>0?"Completed — Retry available":failed>0?"Completed with failures":"Completed"):
                phase.startsWith("Paused")?"Paused":phase.startsWith("Interrupted")||phase.startsWith("Stopped")?"Interrupted":
                phase.startsWith("Waiting")||phase.equals("Checking existing thumbnails")||QueueControl.activeAccounts==0?"Waiting":"Downloading";
        }
        String summary(){return String.format(Locale.ROOT,"Downloaded %d · Existing %d · Failed %d · Skipped %d · Pending %d",downloaded,existing,failed,skipped,pending);}
    }
    static Notification preparing(Context c) {
        channel(c);
        return new Notification.Builder(c,"downloads").setSmallIcon(R.drawable.ic_download_status).setContentTitle("INSFAV Plus Thumbnail Fetcher").setContentText("STARTING · Loading saved queue and index").setProgress(0,0,true).setOnlyAlertOnce(true).setOngoing(true).build();
    }
    static Notification create(Context c,String phase) {
        channel(c);Map<String,Integer> counts;int held;
        try(QueueStore queue=new QueueStore(c)){counts=queue.counts();held=queue.held();}
        Progress progress=new Progress(counts,phase,SessionState.prefs(c).getString("blocked",""),held);
        boolean completed=progress.status.startsWith("Completed"),rate=progress.status.equals("Rate Limited"),failed=progress.status.equals("Failed");
        boolean terminal=completed||rate||failed||progress.status.equals("Interrupted")||progress.status.equals("Paused")||progress.status.equals("Authentication Required");
        PendingIntent open=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String compact=progress.status+" · Downloaded "+progress.downloaded;
        String detail="Downloaded "+progress.downloaded+" · Remaining "+progress.pending
            +"\nExisting "+progress.existing+" · Skipped "+progress.skipped+" · Failed "+progress.failed;
        if(progress.held>0)detail+="\n"+progress.held+" saved for manual Retry";
        android.content.SharedPreferences prefs=SessionState.prefs(c);
        if(rate){long until=prefs.getLong("retryAt",0);detail+="\n"+(prefs.getInt("blockedHttp",0)==429?"HTTP 429":"Instagram rate-limit page")+(until>0?" · Cooldown until "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(until)):" · Cooldown duration unknown")+"\n"+(WorkScheduler.enabled(prefs)?"Automatic resume scheduled within working hours; Android may defer":"Resume manually after cooldown");}
        if(progress.status.equals("Paused")&&prefs.getBoolean("userPaused",false))detail+="\nPaused by user; saved progress retained";
        if(failed)detail+="\n"+prefs.getString("blocked","Unknown error")+" · Queue and images preserved";
        int icon=rate||progress.status.equals("Authentication Required")||progress.status.equals("Completed with failures")?R.drawable.ic_download_warning:failed?R.drawable.ic_download_error:completed?R.drawable.ic_download_done:R.drawable.ic_download_status;
        Notification.Builder builder=new Notification.Builder(c,"downloads").setSmallIcon(icon).setContentTitle("INSFAV Plus Thumbnail Fetcher").setContentText(compact)
            .setStyle(new Notification.BigTextStyle().bigText(detail)).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(!terminal).setAutoCancel(terminal)
            .setProgress(Math.max(1,progress.total),progress.done,false);
        if(progress.status.equals("Starting")){int total=prefs.getInt("indexTotal",0),processed=prefs.getInt("indexProcessed",0);builder.setProgress(total,processed,total==0).setContentText(total>0?"STARTING · Indexing "+processed+" / "+total:"STARTING · Loading persistent index");}
        if(rate&&prefs.getLong("retryAt",0)>System.currentTimeMillis())builder.setWhen(prefs.getLong("retryAt",0)).setUsesChronometer(true).setChronometerCountDown(true);
        if(completed)builder.setTimeoutAfter(6000);
        if(!terminal)for(String action:new String[]{"pause","stop"}){
            PendingIntent control=PendingIntent.getBroadcast(c,action.equals("pause")?1:2,new Intent(c,QueueControlReceiver.class).setAction(action),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(null,action.equals("pause")?"Pause":"Stop",control).build());
        }
        return builder.build();
    }
    static synchronized void publish(Context c,int id,String phase) {
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-lastAt<1000&&phase.equals(lastPhase))return;
        // Changing between busy/waiting on each account must not flood NotificationManager.
        boolean terminal=phase.equals("Completed")||phase.startsWith("Finished:")||phase.equals("Rate Limited")||phase.startsWith("Paused")||phase.startsWith("Interrupted")||phase.startsWith("Stopped");
        if(!terminal&&now-lastAt<1000)return;
        lastAt=now;lastPhase=phase;
        try{c.getSystemService(NotificationManager.class).notify(id,create(c,phase));}catch(RuntimeException ignored){}
    }
    static void finish(Context c) {
        try{c.getSystemService(NotificationManager.class).notify(3,Notification.Builder.recoverBuilder(c,create(c,SessionState.prefs(c).getString("state","Interrupted; queue preserved"))).setOngoing(false).setAutoCancel(true).build());}catch(RuntimeException ignored){}
    }
}
