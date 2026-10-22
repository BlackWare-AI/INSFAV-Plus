package com.instagram.insfav.fetcher;
import android.content.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Shared cookie lease and reusable WebViews; dispatch pacing never holds active operations. */
final class FetchBroker {
 interface Time{long now();void sleep(long millis)throws InterruptedException;default void await(Object monitor,long millis)throws InterruptedException{if(millis==0)monitor.wait();else sleep(millis);}}
 interface Factory{ProfileProvider open(boolean full)throws ProfileProvider.Failure;}
 private static FetchBroker instance;
 static synchronized FetchBroker get(Context c){if(instance==null){Context app=c.getApplicationContext();instance=new FetchBroker(app,full->new WebViewProfileProvider(app,new CookieVault(app).load()));}return instance;}
 private final Object signal=new Object();private final AtomicLong signals=new AtomicLong();final AtomicLong loopPasses=new AtomicLong(),waits=new AtomicLong();
 private final SharedPreferences.OnSharedPreferenceChangeListener preferenceWake=(p,key)->wakeup();
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
 FetchBroker(Context c,Factory f){this(c,f,new Time(){public long now(){return System.currentTimeMillis();}public void sleep(long m)throws InterruptedException{Thread.sleep(m);}public void await(Object monitor,long millis)throws InterruptedException{monitor.wait(millis);}});}
 FetchBroker(Context c,Factory f,Time t){this(c,f,t,requested->ConcurrencyPolicy.initial(c,requested));}
 FetchBroker(Context c,Factory f,Time t,java.util.function.IntUnaryOperator admission){this.admission=admission;context=c.getApplicationContext();prefs=SessionState.prefs(c);prefs.registerOnSharedPreferenceChangeListener(preferenceWake);factory=f;time=t;Thread thread=new Thread(this::loop,"insfav-profile-dispatcher");thread.setDaemon(true);thread.start();}
 void beginBatch(){authenticationRequired=true;prefs.edit().putString("sessionEvidence","UNKNOWN").putLong("diagnosticBatch",prefs.getLong("diagnosticBatch",0)+1).commit();new Diagnostics(context).log("BATCH","STARTED",prefs.getLong("diagnosticBatch",0));batch=true;limit=admission.applyAsInt(prefs.getInt("concurrentWebViews",1));QueueControl.effectiveViews=limit;QueueControl.peakAccounts=0;QueueControl.memoryPressure=false;Diagnostics d=new Diagnostics(context);d.log("POOL","REQUESTED_LIMIT",prefs.getInt("concurrentWebViews",1));d.log("POOL","EFFECTIVE_LIMIT",limit);}
 void endBatch(){batch=false;wakeup();}
 int capacity(){return limit;}
 // Dispatch waits are visible without changing the user's delay or worker setting.
 long nextDispatchAt(){
  if(tasks.isEmpty()||authenticating!=null||active.size()>=limit)return 0;
  boolean[] busy=new boolean[30];for(Request a:active)if(a.slot>=0)busy[a.slot]=true;
  long earliest=Long.MAX_VALUE;for(int i=0;i<limit;i++)if(!busy[i])earliest=Math.min(earliest,WorkerPacing.ready(prefs,i));
  long gate=Math.max(prefs.getLong("nextRequestAt",0),prefs.getLong("retryAt",0));
  return earliest==Long.MAX_VALUE?0:Math.max(gate,earliest);
 }
 CompletableFuture<byte[]> submit(String user,boolean full){Request r=new Request(user,full,sequence.incrementAndGet());tasks.add(r);wakeup();QueueControl.queuedAccounts=tasks.size();return r.result;}
 void cancelBulk(){cancel(false);wakeup();}void cancelViewer(){cancel(true);wakeup();}void cancelAll(){cancel(false);cancel(true);wakeup();}
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
 private long nextDeadlineDelay(){
  long nearest=Long.MAX_VALUE;
  for(Request request:active)if(!request.result.isDone())nearest=Math.min(nearest,request.deadline-System.nanoTime());
  return nearest==Long.MAX_VALUE?0:Math.max(1,TimeUnit.NANOSECONDS.toMillis(nearest)+1);
 }
 private long boundedWait(long requested,boolean bulk){
  long deadline=nextDeadlineDelay();long delay=requested==0?deadline:deadline==0?requested:Math.min(requested,deadline);
  if(bulk){long now=time.now(),end=RunPolicy.read(prefs).windowEnd(now,java.time.ZoneId.systemDefault());long boundary=Math.max(1,end-now);delay=delay==0?boundary:Math.min(delay,boundary);}
  return delay;
 }
 private void awaitEvent(long observed,long millis)throws InterruptedException{
  synchronized(signal){if(signals.get()==observed){waits.incrementAndGet();signal.wait(millis);}}
 }
 private void awaitPacing(long observed,long millis)throws InterruptedException{synchronized(signal){if(signals.get()==observed){waits.incrementAndGet();time.await(signal,millis);}}}
 void wakeup(){synchronized(signal){signals.incrementAndGet();signal.notifyAll();}}
 private void releaseProvider(){
  ProfileProvider previous=provider;provider=null;if(previous==null)return;
  try{previous.close();}catch(RuntimeException error){new Diagnostics(context).unexpected("BROKER",error);prefs.edit().putString("blocked","WEBVIEW_CLEANUP_FAILED").commit();}
 }
 private void loop(){for(;;)try{
  long observed=signals.get();loopPasses.incrementAndGet();
  for(Request request:active)if(System.nanoTime()>request.deadline&&!request.result.isDone())cancel(request,true);
  if(QueueControl.memoryPressure){limit=ConcurrencyPolicy.reduced(limit);QueueControl.effectiveViews=limit;QueueControl.memoryPressure=false;ProfileProvider current=provider;if(current!=null)current.trim(limit);new Diagnostics(context).log("POOL","MEMORY_PRESSURE_LIMIT",limit);Fetcher.publish();}
  if(active.isEmpty()&&tasks.isEmpty()&&!batch)releaseProvider();
  if(provider!=null&&active.isEmpty()&&(poisoned||provider.blockingFailure()!=null||!sessionGeneration.equals(prefs.getString("cookieGeneration","")))){releaseProvider();authenticationRequired=true;}
  QueueControl.queuedAccounts=tasks.size();Request request=tasks.peek();
  if(request==null||active.size()>=limit){awaitEvent(observed,boundedWait(0,request!=null&&!request.full));continue;}
  if(request.result.isCancelled()){tasks.remove(request);continue;}
  String blocked=prefs.getString("blocked","");
  if(!blocked.isEmpty()){tasks.remove(request);request.result.completeExceptionally(new ProfileProvider.Failure(blocked,0,prefs.getLong("retryAt",0),true));continue;}
  if(!request.full&&!RunPolicy.read(prefs).contains(java.time.Instant.ofEpochMilli(time.now()).atZone(java.time.ZoneId.systemDefault()))){tasks.remove(request);request.result.completeExceptionally(new ProfileProvider.Failure("OUTSIDE_WORKING_HOURS"));continue;}
  long now=time.now(),gate=Math.max(prefs.getLong("nextRequestAt",0),prefs.getLong("retryAt",0));
  if(now<gate){awaitPacing(observed,boundedWait(gate-now,!request.full));continue;}
  boolean[] busy=new boolean[30];for(Request operation:active)if(operation.slot>=0)busy[operation.slot]=true;
  int slot=-1;long earliest=Long.MAX_VALUE;
  for(int i=0;i<limit;i++)if(!busy[i]){long ready=WorkerPacing.ready(prefs,i);if(ready<=now){slot=i;break;}earliest=Math.min(earliest,ready);}
  if(slot<0){awaitPacing(observed,boundedWait(Math.max(1,earliest-now),!request.full));continue;}
  if((request.full&&!active.isEmpty())||active.stream().anyMatch(operation->operation.full)||(!request.full&&batch&&authenticationRequired&&!active.isEmpty())){awaitEvent(observed,boundedWait(0,!request.full));continue;}
  tasks.remove(request);request.slot=slot;WorkerPacing.reserve(prefs,slot,now,RunPolicy.read(prefs));
  try{
   if(provider==null){provider=factory.open(false);authenticationRequired=true;poisoned=false;sessionGeneration=prefs.getString("cookieGeneration","");}
   provider.trim(limit);authenticateBatch(request,provider);if(request.result.isCancelled())continue;
  }catch(ProfileProvider.Failure error){if(FailurePolicy.pause(error))block(error);request.result.completeExceptionally(error);continue;}
  catch(OutOfMemoryError error){ProfileProvider.Failure failure=new ProfileProvider.Failure("IMAGE_MEMORY_LIMIT",0,0,true);block(failure);request.result.completeExceptionally(failure);continue;}
  catch(RuntimeException error){new Diagnostics(context).unexpected("BROKER",error);ProfileProvider.Failure failure=new ProfileProvider.Failure(error instanceof android.database.sqlite.SQLiteException?"DATABASE_ERROR":error instanceof SecurityException?"STORAGE_PERMISSION_LOST":"PROCESSING_ERROR",0,0,true);block(failure);request.result.completeExceptionally(failure);continue;}
  request.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(110);active.add(request);QueueControl.activeAccounts=active.size();QueueControl.peakAccounts=Math.max(QueueControl.peakAccounts,active.size());
  workers.execute(()->execute(request));
 }catch(InterruptedException ignored){}catch(OutOfMemoryError error){block(new ProfileProvider.Failure("IMAGE_MEMORY_LIMIT",0,0,true));}catch(RuntimeException error){new Diagnostics(context).unexpected("BROKER",error);block(new ProfileProvider.Failure("PROCESSING_ERROR",0,0,true));}}
 private void execute(Request r){r.thread=Thread.currentThread();byte[] bytes=null;try{
  if(r.result.isCancelled())return;if(!r.full&&!RunPolicy.read(prefs).contains(java.time.Instant.ofEpochMilli(time.now()).atZone(java.time.ZoneId.systemDefault())))throw new ProfileProvider.Failure("OUTSIDE_WORKING_HOURS");ProfileProvider p=provider;bytes=p.fetch(r.user,r.full);if(!r.result.complete(bytes))Arrays.fill(bytes,(byte)0);
 }catch(ProfileProvider.Failure e){if(FailurePolicy.pause(e))block(e);r.result.completeExceptionally(e);if(e.code.equals("WEBVIEW_RENDERER_GONE"))QueueControl.memoryPressure=true;}
 catch(SecurityException|android.database.sqlite.SQLiteException e){new Diagnostics(context).unexpected("PROFILE",e);r.result.completeExceptionally(e);}
 catch(OutOfMemoryError e){ProfileProvider.Failure memory=new ProfileProvider.Failure("IMAGE_MEMORY_LIMIT",0,0,true);block(memory);r.result.completeExceptionally(memory);}
  catch(RuntimeException e){new Diagnostics(context).unexpected("PROFILE",e);r.result.completeExceptionally(new ProfileProvider.Failure("PROFILE_PROCESSING_ERROR"));}
 finally{active.remove(r);QueueControl.activeAccounts=active.size();r.thread=null;Thread.interrupted();wakeup();Fetcher.publish();}}
}
