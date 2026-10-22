package com.instagram.insfav.fetcher;
import android.content.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Shared cookie lease and reusable WebViews; dispatch pacing never holds active operations. */
final class FetchBroker {
 interface Time{long now();void sleep(long millis)throws InterruptedException;}
 interface Factory{ProfileProvider open(boolean full)throws ProfileProvider.Failure;}
 private static FetchBroker instance;
 static synchronized FetchBroker get(Context c){if(instance==null){Context app=c.getApplicationContext();instance=new FetchBroker(app,full->new WebViewProfileProvider(app,new CookieVault(app).load()));}return instance;}
 final SharedPreferences prefs;final Factory factory;final Time time;final Context context;final java.util.function.IntUnaryOperator admission;
 final PriorityBlockingQueue<Request> tasks=new PriorityBlockingQueue<>();final Set<Request> active=ConcurrentHashMap.newKeySet();
 final AtomicLong sequence=new AtomicLong();final ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"insfav-profile-worker");t.setDaemon(true);return t;});
 private volatile String sessionGeneration="";
 volatile Request authenticating; private volatile boolean authenticationRequired=true;
 volatile ProfileProvider provider;volatile boolean poisoned;volatile int limit=1;volatile boolean batch;
 static final class Request implements Comparable<Request>{
  final String user;final boolean full;final long sequence;final CompletableFuture<byte[]> result=new CompletableFuture<>();volatile Thread thread;volatile long deadline;int slot=-1;
  Request(String user,boolean full,long sequence){this.user=user;this.full=full;this.sequence=sequence;}
  public int compareTo(Request other){int n=Boolean.compare(other.full,full);return n!=0?n:Long.compare(sequence,other.sequence);}
 }
 FetchBroker(Context c,Factory f){this(c,f,new Time(){public long now(){return System.currentTimeMillis();}public void sleep(long m)throws InterruptedException{Thread.sleep(m);}});}
 FetchBroker(Context c,Factory f,Time t){this(c,f,t,requested->ConcurrencyPolicy.initial(c,requested));}
 FetchBroker(Context c,Factory f,Time t,java.util.function.IntUnaryOperator admission){this.admission=admission;context=c.getApplicationContext();prefs=SessionState.prefs(c);factory=f;time=t;Thread thread=new Thread(this::loop,"insfav-profile-dispatcher");thread.setDaemon(true);thread.start();}
 void beginBatch(){authenticationRequired=true;prefs.edit().putString("sessionEvidence","UNKNOWN").putLong("diagnosticBatch",prefs.getLong("diagnosticBatch",0)+1).commit();new Diagnostics(context).log("BATCH","STARTED",prefs.getLong("diagnosticBatch",0));batch=true;limit=admission.applyAsInt(prefs.getInt("concurrentWebViews",1));QueueControl.effectiveViews=limit;QueueControl.peakAccounts=0;QueueControl.memoryPressure=false;Diagnostics d=new Diagnostics(context);d.log("POOL","REQUESTED_LIMIT",prefs.getInt("concurrentWebViews",1));d.log("POOL","EFFECTIVE_LIMIT",limit);}
 void endBatch(){batch=false;}
 int capacity(){return limit;}
 // Dispatch waits are visible without changing the user's delay or worker setting.
 long nextDispatchAt(){
  if(tasks.isEmpty()||authenticating!=null||active.size()>=limit)return 0;
  boolean[] busy=new boolean[30];for(Request a:active)if(a.slot>=0)busy[a.slot]=true;
  long earliest=Long.MAX_VALUE;for(int i=0;i<limit;i++)if(!busy[i])earliest=Math.min(earliest,WorkerPacing.ready(prefs,i));
  long gate=Math.max(prefs.getLong("nextRequestAt",0),prefs.getLong("retryAt",0));
  return earliest==Long.MAX_VALUE?0:Math.max(gate,earliest);
 }
 CompletableFuture<byte[]> submit(String user,boolean full){Request r=new Request(user,full,sequence.incrementAndGet());tasks.add(r);QueueControl.queuedAccounts=tasks.size();return r.result;}
 void cancelBulk(){cancel(false);}void cancelViewer(){cancel(true);}void cancelAll(){cancel(false);cancel(true);}
 private void cancel(boolean full){Request auth=authenticating;if(auth!=null&&auth.full==full){poisoned=true;cancel(auth,false);ProfileProvider p=provider;if(p!=null)p.cancel();}for(Request r:tasks)if(r.full==full){tasks.remove(r);r.result.cancel(false);}for(Request r:active)if(r.full==full)cancel(r,false);}
 private void cancel(Request r,boolean timeout){if(timeout)r.result.completeExceptionally(new ProfileProvider.Failure("PROFILE_OPERATION_TIMEOUT"));else r.result.cancel(false);ProfileProvider p=provider;if(p!=null)p.cancel(r.user);Thread thread=r.thread;if(thread!=null)thread.interrupt();}
 private void block(ProfileProvider.Failure e){poisoned=true;prefs.edit().putString("sessionEvidence",SessionState.invalid(e)?"UNVERIFIED":SessionState.evidence(prefs)).putString("blocked",e.code).putInt("blockedHttp",e.http).putLong("retryAt",Math.max(prefs.getLong("retryAt",0),e.retryAt)).putString("state",SessionState.pausedText(e.code)).commit();ProfileProvider p=provider;if(p!=null)p.cancel();for(Request a:active)if(a.thread!=Thread.currentThread())cancel(a,false);}
 private void authenticateBatch(Request r, ProfileProvider p)throws ProfileProvider.Failure {
  if(r.full||!batch||!authenticationRequired)return;
  authenticating=r;r.thread=Thread.currentThread();
  try {
   p.authenticate();
   if(r.result.isCancelled())throw new ProfileProvider.Failure("REQUEST_CANCELLED");
   authenticationRequired=false;prefs.edit().putString("sessionEvidence","VERIFIED").commit();
  } catch(ProfileProvider.Failure e) {
   poisoned=true;
   if(e.code.equals("REQUEST_CANCELLED"))throw e;
   if(FailurePolicy.pause(e))throw e;
   // An inconclusive/network-limited verification is a timed wait, never expired cookies.
   long retry=System.currentTimeMillis()+60000;
   prefs.edit().putString("sessionEvidence","UNKNOWN").putLong("retryAt",Math.max(prefs.getLong("retryAt",0),retry)).putString("state","Waiting: session verification retry").commit();
   throw new ProfileProvider.Failure("COOKIE_AUTH_UNVERIFIED",e.http,retry,false);
  } finally {authenticating=null;r.thread=null;Thread.interrupted();}
 }
 private void loop(){for(;;)try{
  for(Request r:active)if(System.nanoTime()>r.deadline&&!r.result.isDone())cancel(r,true);
  if(QueueControl.memoryPressure){limit=ConcurrencyPolicy.reduced(limit);QueueControl.effectiveViews=limit;QueueControl.memoryPressure=false;ProfileProvider p=provider;if(p!=null)p.trim(limit);new Diagnostics(context).log("POOL","MEMORY_PRESSURE_LIMIT",limit);Fetcher.publish();}
  if(active.isEmpty()&&tasks.isEmpty()&&!batch){ProfileProvider p=provider;provider=null;if(p!=null)p.close();}
  if(provider!=null&&active.isEmpty()&&(poisoned||provider.blockingFailure()!=null||!sessionGeneration.equals(prefs.getString("cookieGeneration","")))){ProfileProvider p=provider;provider=null;p.close();authenticationRequired=true;}
  QueueControl.queuedAccounts=tasks.size();Request r=tasks.peek();if(r==null||active.size()>=limit){Thread.sleep(20);continue;}
  if(r.result.isCancelled()){tasks.remove(r);continue;}
  String blocked=prefs.getString("blocked","");if(!blocked.isEmpty()){tasks.remove(r);r.result.completeExceptionally(new ProfileProvider.Failure(blocked,0,prefs.getLong("retryAt",0),true));continue;}
  if(!r.full&&!RunPolicy.read(prefs).contains(java.time.Instant.ofEpochMilli(time.now()).atZone(java.time.ZoneId.systemDefault()))){tasks.remove(r);r.result.completeExceptionally(new ProfileProvider.Failure("OUTSIDE_WORKING_HOURS"));continue;}
  long now=time.now(),gate=Math.max(prefs.getLong("nextRequestAt",0),prefs.getLong("retryAt",0));if(now<gate){time.sleep(Math.min(50,gate-now));continue;}
  boolean[] busy=new boolean[30];for(Request a:active)if(a.slot>=0)busy[a.slot]=true;
  int slot=-1;long earliest=Long.MAX_VALUE;for(int i=0;i<limit;i++)if(!busy[i]){long ready=WorkerPacing.ready(prefs,i);if(ready<=now){slot=i;break;}earliest=Math.min(earliest,ready);}
  if(slot<0){time.sleep(Math.min(50,Math.max(1,earliest-now)));continue;}
  // Interactive work drains existing batch operations, then owns the same session at a safe boundary.
  if((r.full&&!active.isEmpty())||active.stream().anyMatch(a->a.full)){Thread.sleep(20);continue;}
  if(!r.full&&batch&&authenticationRequired&&!active.isEmpty()){Thread.sleep(20);continue;}
  tasks.remove(r);r.slot=slot;WorkerPacing.reserve(prefs,slot,now,RunPolicy.read(prefs));
  try{if(provider==null){provider=factory.open(false);authenticationRequired=true;poisoned=false;sessionGeneration=prefs.getString("cookieGeneration","");}provider.trim(limit);authenticateBatch(r,provider);if(r.result.isCancelled())continue;}catch(ProfileProvider.Failure e){if(FailurePolicy.pause(e))block(e);r.result.completeExceptionally(e);continue;}catch(OutOfMemoryError e){ProfileProvider.Failure error=new ProfileProvider.Failure("IMAGE_MEMORY_LIMIT",0,0,true);block(error);r.result.completeExceptionally(error);continue;}catch(RuntimeException e){ProfileProvider.Failure error=new ProfileProvider.Failure("PROCESSING_ERROR",0,0,true);block(error);r.result.completeExceptionally(error);continue;}
  r.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(110);active.add(r);QueueControl.activeAccounts=active.size();QueueControl.peakAccounts=Math.max(QueueControl.peakAccounts,active.size());
  workers.execute(()->execute(r));
 }catch(InterruptedException ignored){}catch(OutOfMemoryError e){block(new ProfileProvider.Failure("IMAGE_MEMORY_LIMIT",0,0,true));}catch(RuntimeException e){block(new ProfileProvider.Failure("PROCESSING_ERROR",0,0,true));}}
 private void execute(Request r){r.thread=Thread.currentThread();byte[] bytes=null;try{
  if(r.result.isCancelled())return;if(!r.full&&!RunPolicy.read(prefs).contains(java.time.Instant.ofEpochMilli(time.now()).atZone(java.time.ZoneId.systemDefault())))throw new ProfileProvider.Failure("OUTSIDE_WORKING_HOURS");ProfileProvider p=provider;bytes=p.fetch(r.user,r.full);if(!r.result.complete(bytes))Arrays.fill(bytes,(byte)0);
 }catch(ProfileProvider.Failure e){if(FailurePolicy.pause(e))block(e);r.result.completeExceptionally(e);if(e.code.equals("WEBVIEW_RENDERER_GONE"))QueueControl.memoryPressure=true;}
 catch(SecurityException|android.database.sqlite.SQLiteException e){new Diagnostics(context).unexpected("PROFILE",e);r.result.completeExceptionally(e);}
 catch(OutOfMemoryError e){ProfileProvider.Failure memory=new ProfileProvider.Failure("IMAGE_MEMORY_LIMIT",0,0,true);block(memory);r.result.completeExceptionally(memory);}
  catch(RuntimeException e){new Diagnostics(context).unexpected("PROFILE",e);r.result.completeExceptionally(new ProfileProvider.Failure("PROFILE_PROCESSING_ERROR"));}
 finally{active.remove(r);QueueControl.activeAccounts=active.size();r.thread=null;Thread.interrupted();Fetcher.publish();}}
}
