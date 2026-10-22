package com.instagram.insfav.fetcher;
import android.content.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
/** Coordinator alone owns queue and SAF commits. Independent broker operations overlap. */
final class QueueEngine {
 static int terminalCount(Map<String,Integer> counts){return counts.getOrDefault("SUCCESS",0)+counts.getOrDefault("FAILED",0)+counts.getOrDefault("SKIPPED_UNAVAILABLE",0)+counts.getOrDefault("SKIPPED_NO_IMAGE",0);}
 interface Reporter{void update(String state);}
 final Context context;final SharedPreferences settings;final Reporter reporter;final BooleanSupplier stopped;
 private long lastSignal;private String lastState="";volatile boolean cancelled;volatile String cancelledState="Interrupted; queue preserved";
 QueueEngine(Context c,Reporter r,BooleanSupplier stop){context=c;settings=SessionState.prefs(c);reporter=r;stopped=stop;}
 void cancel(){cancelled=true;FetchBroker.get(context).cancelBulk();}void cancel(String text){cancelledState=text;cancel();}
 boolean interrupted(){return cancelled||stopped.getAsBoolean()||QueueControl.requested||Thread.currentThread().isInterrupted();}
 void state(String value){long now=android.os.SystemClock.elapsedRealtime();if(value.equals(lastState)&&now-lastSignal<250)return;lastState=value;lastSignal=now;QueueControl.phase=value;settings.edit().putString("state",value).apply();reporter.update(value);Fetcher.publish();}
 void run(String action,boolean bounded){
  long started=System.currentTimeMillis(),generation=settings.getLong("datasetGeneration",0);int finished=0,cached=0;FetchBroker broker=FetchBroker.get(context);Map<String,CompletableFuture<byte[]>> flights=new LinkedHashMap<>();boolean hoursEnded=false;
  QueueControl.completedPerMinute=0;QueueControl.runProcessed=0;QueueControl.lastResult="";QueueControl.etaSeconds=-1;
  settings.edit().putLong("startedAt",started).apply();broker.beginBatch();
  try(QueueStore queue=new QueueStore(context)){
   state("Starting — Loading persistent index");queue.recoverInterrupted();
   if(action.equals("missing"))queue.prepareMissing();
   FetcherCatalog.seed(queue);
   if(action.equals("retry_skipped"))queue.retrySkipped();

   OutputFolder output=new OutputFolder(context,settings.getString("tree",""),queue,action.equals("verify_index"),()->state("Starting — Verifying thumbnail index"));
   if(action.equals("verify_index")){state("Paused — Fetcher index verified");return;}
   if("RATE_LIMITED".equals(settings.getString("blocked",""))&&System.currentTimeMillis()>=settings.getLong("retryAt",0))settings.edit().putString("blocked","").commit();
   ArrayDeque<String> targets=new ArrayDeque<>(queue.eligible());
   while(!interrupted()){
    if(generation!=settings.getLong("datasetGeneration",0)){state("Paused: collections or folder changed");return;}
    if(!settings.getString("blocked","").isEmpty()){for(String user:flights.keySet())queue.mark(user,"RETRY_LATER",settings.getString("blocked",""));state("RATE_LIMITED".equals(settings.getString("blocked",""))?"Rate Limited":SessionState.pausedText(settings.getString("blocked","")));return;}
    if(!RunPolicy.read(settings).contains(ZonedDateTime.now()))hoursEnded=true;
    if(bounded&&System.currentTimeMillis()-started>=120000)hoursEnded=true;
    while(!hoursEnded&&!targets.isEmpty()&&flights.size()<broker.capacity()&&!interrupted()){
     if(!RunPolicy.read(settings).contains(ZonedDateTime.now())){hoursEnded=true;break;}
     String user=targets.remove();String filename=Identity.outputFilename(user);boolean validOutput=output.valid(filename);
     if(validOutput){queue.mark(user,"EXISTING","LOCAL_IMAGE");state("Checking existing thumbnails");if(bounded&&++cached>=128&&flights.isEmpty())return;continue;}
     if(!queue.claim(user))continue;QueueControl.current=user;flights.put(user,broker.submit(user,false));state("Downloading");
    }
    if(flights.isEmpty()){
     if(hoursEnded){state("Paused — Outside Schedule");return;}
     if(targets.isEmpty()){targets.addAll(queue.eligible());if(targets.isEmpty()){if(queue.counts().getOrDefault("RETRY_LATER",0)>0){state("Finished: previous failures saved; use Retry to try again");return;}settings.edit().putBoolean("armed",false).commit();state("Completed");return;}}
     continue;
    }
    boolean completed=false;
    for(Iterator<Map.Entry<String,CompletableFuture<byte[]>>> it=flights.entrySet().iterator();it.hasNext();){Map.Entry<String,CompletableFuture<byte[]>> f=it.next();if(!f.getValue().isDone())continue;completed=true;String user=f.getKey();byte[] original=null;boolean committing=false;
     try{
      original=f.getValue().get();byte[] thumbnail=ThumbnailCodec.convert(original);if(interrupted())break;
      if(generation!=settings.getLong("datasetGeneration",0)){state("Paused: data changed");return;}
      committing=true;android.net.Uri uri=output.commit(Identity.outputFilename(user),thumbnail,false);Fetcher.host().imageWritten(user,uri,settings.getString("tree",""));queue.mark(user,output.lastWriteCreated?"SUCCESS":"EXISTING","VALID_WEBP");QueueControl.lastResult=output.lastWriteCreated?"SUCCESS":"EXISTING";new Diagnostics(context).log("PROFILE","SUCCESS",0);
     }catch(ExecutionException e){Throwable cause=e.getCause();ProfileProvider.Failure error=cause instanceof ProfileProvider.Failure?(ProfileProvider.Failure)cause:new ProfileProvider.Failure("PROFILE_PROCESSING_ERROR");QueueControl.lastResult=error.code;
      if(error.code.equals("OUTSIDE_WORKING_HOURS")){hoursEnded=true;}
      else if(FailurePolicy.sessionTransient(error)){queue.mark(user,"RETRY_LATER",error.code);state("Waiting: session verification retry");return;}
      else if(FailurePolicy.pause(error)){queue.mark(user,"RETRY_LATER",error.code);state(error.code.equals("RATE_LIMITED")?"Rate Limited":SessionState.pausedText(error.code));return;}
      else if(FailurePolicy.unavailable(error))queue.mark(user,"SKIPPED_UNAVAILABLE",error.code);
      else if(FailurePolicy.noImage(error))queue.mark(user,"SKIPPED_NO_IMAGE",error.code);
      else if(FailurePolicy.retry(error)){queue.attempted(user);queue.mark(user,"RETRY_LATER",error.code);}
      else{queue.attempted(user);queue.mark(user,"FAILED",error.code);}
      QueueControl.lastResult=error.code;new Diagnostics(context).log("PROFILE",error.code,error.http);
     }catch(CancellationException e){queue.mark(user,"RETRY_LATER","REQUEST_CANCELLED");}
     catch(IOException e){if(committing)throw e;queue.attempted(user);QueueControl.lastResult=Diagnostics.imageCode(e);queue.mark(user,"FAILED",QueueControl.lastResult);}
     catch(SecurityException|android.database.sqlite.SQLiteException e){throw e;}
     catch(RuntimeException e){QueueControl.lastResult="PROFILE_PROCESSING_ERROR";queue.attempted(user);queue.mark(user,"FAILED","PROFILE_PROCESSING_ERROR");new Diagnostics(context).log("PROFILE","PROFILE_PROCESSING_ERROR",0);}
     finally{if(original!=null)Arrays.fill(original,(byte)0);it.remove();}
     Map<String,Integer> totals=queue.counts();finished=++QueueControl.runProcessed;long elapsed=Math.max(1,System.currentTimeMillis()-started);QueueControl.completedPerMinute=finished*60000.0/elapsed;QueueControl.etaSeconds=finished>=3?(long)(Math.max(0,totals.getOrDefault("PENDING",0)+totals.getOrDefault("RETRY_LATER",0)-queue.held())*60.0/QueueControl.completedPerMinute):-1;state("Downloading");
    }
    if(!completed){if(android.os.SystemClock.elapsedRealtime()-lastSignal>=1000)state(broker.authenticating!=null?"Verifying session":QueueControl.activeAccounts==0?"Waiting for worker delay":"Downloading");Thread.sleep(25);}
   }
   state(QueueControl.requested?QueueControl.requestedState:cancelledState);
  }catch(SecurityException e){settings.edit().putString("blocked","STORAGE_PERMISSION_LOST").commit();state("Paused: folder write permission required");}
  catch(android.database.sqlite.SQLiteException e){settings.edit().putString("blocked","DATABASE_ERROR").commit();state("Paused: database error; queue preserved");}
  catch(IOException e){String code=Diagnostics.storageCode(e);settings.edit().putString("blocked",code).commit();new Diagnostics(context).log("STORAGE",code,0);state("Paused: storage access error; queue preserved");}
  catch(OutOfMemoryError e){broker.cancelBulk();settings.edit().putString("blocked","IMAGE_MEMORY_LIMIT").commit();state("Paused: memory limit; queue preserved");}
  catch(InterruptedException e){Thread.currentThread().interrupt();state(cancelledState);}
  catch(Exception e){settings.edit().putString("blocked","PROCESSING_ERROR").commit();state("Paused: processing error; queue preserved");}
  finally{broker.cancelBulk();try(QueueStore q=new QueueStore(context)){q.deferClaims("REQUEST_CANCELLED");q.meta("sourceRevision",Long.toString(Fetcher.host().revision()));}catch(Exception ignored){}broker.endBatch();settings.edit().putLong("elapsedSeconds",settings.getLong("elapsedSeconds",0)+Math.max(0,(System.currentTimeMillis()-started)/1000)).putLong("startedAt",0).apply();QueueControl.current="";QueueControl.etaSeconds=-1;Fetcher.publish();}
 }
}
