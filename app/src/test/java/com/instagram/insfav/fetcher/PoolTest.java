package com.instagram.insfav.fetcher;
import android.content.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class PoolTest{
 Context c;
 @Before public void setup(){c=RuntimeEnvironment.getApplication();QueueControl.memoryPressure=false;SessionState.prefs(c).edit().clear().putInt("minimum",1).putInt("maximum",1).putInt("startMinute",0).putInt("endMinute",1439).commit();}
 @Test public void admissionUsesAvailableMemoryRatherThanMemoryClassDivision(){assertEquals(5,ConcurrencyPolicy.budget(5,4L<<30,256L<<20,false,256L<<20));assertEquals(30,ConcurrencyPolicy.budget(30,8L<<30,256L<<20,false,512L<<20));assertEquals(1,ConcurrencyPolicy.budget(30,8L<<30,256L<<20,true,512L<<20));assertEquals(1,ConcurrencyPolicy.budget(30,300L<<20,256L<<20,false,512L<<20));assertEquals(5,ConcurrencyPolicy.reduced(10));}
 @Test(timeout=15000) public void matrixOverlapsActualProviderOperationsAndPrioritizesViewer()throws Exception{
  for(int requested:new int[]{1,2,5,10,30}){
   AtomicInteger current=new AtomicInteger(),peak=new AtomicInteger();List<String> starts=Collections.synchronizedList(new ArrayList<>());CountDownLatch first=new CountDownLatch(1),release=new CountDownLatch(1);
   FetchBroker b=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String user)throws Failure{int n=current.incrementAndGet();peak.accumulateAndGet(n,Math::max);starts.add(user);first.countDown();try{release.await(3,TimeUnit.SECONDS);return new byte[]{1};}catch(InterruptedException e){throw new Failure("REQUEST_CANCELLED");}finally{current.decrementAndGet();}}public void cancel(){}},new TestTime());
   b.limit=requested;List<CompletableFuture<byte[]>> fs=new ArrayList<>();for(int i=0;i<requested;i++)fs.add(b.submit("profile"+i,false));assertTrue(first.await(2,TimeUnit.SECONDS));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(peak.get()<requested&&System.nanoTime()<until)Thread.sleep(5);assertEquals(requested,peak.get());release.countDown();for(CompletableFuture<byte[]> f:fs)f.get(3,TimeUnit.SECONDS);
  }
 }
 @Test public void skippedRetryIsExplicitAndSurvivesDatabaseReopen(){c.deleteDatabase("fetcher-queue.db");try(QueueStore q=new QueueStore(c)){q.sync(Arrays.asList("gone","empty","done"));q.mark("gone","SKIPPED_UNAVAILABLE","PROFILE_PAGE_UNAVAILABLE");q.mark("empty","SKIPPED_NO_IMAGE","PROFILE_IMAGE_URL_MISSING");q.mark("done","SUCCESS","VALID_WEBP");q.prepareMissing();assertTrue(q.pending().isEmpty());}try(QueueStore q=new QueueStore(c)){q.retrySkipped();assertEquals(Arrays.asList("gone","empty"),q.pending());assertEquals(Integer.valueOf(1),q.counts().get("SUCCESS"));}}
 @Test public void backgroundEnabledDoesNotPauseWhenActivityStops(){Fetcher.initialize(c,new Fetcher.Host(){public Collection<String> users(){return Collections.emptyList();}public String tree(){return "";}public String directory(){return "";}public long revision(){return 0;}public boolean hasImage(String u){return false;}public void imageWritten(String u,android.net.Uri uri,String tree){}});SessionState.prefs(c).edit().putBoolean("background",true).putBoolean("armed",true).putBoolean("userPaused",false).commit();QueueControl.lock.set(true);QueueControl.requested=false;try{Fetcher.foregroundStopped();assertFalse(QueueControl.requested);assertTrue(WorkScheduler.enabled(SessionState.prefs(c)));}finally{QueueControl.lock.set(false);}}
 @Test(timeout=10000) public void closingOneRequestDoesNotCancelOtherAccounts()throws Exception{
  CountDownLatch both=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger cancelled=new AtomicInteger();
  FetchBroker b=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String u)throws Failure{both.countDown();try{release.await();return new byte[]{7};}catch(InterruptedException e){throw new Failure("REQUEST_CANCELLED");}}public void cancel(){cancelled.incrementAndGet();}},new TestTime());b.limit=2;
  CompletableFuture<byte[]> a=b.submit("first",false),d=b.submit("second",false);assertTrue(both.await(2,TimeUnit.SECONDS));a.cancel(false);release.countDown();assertArrayEquals(new byte[]{7},d.get(3,TimeUnit.SECONDS));assertEquals(0,cancelled.get());
 }
 @Test(timeout=10000) public void completingOperationDoesNotExtendReservedStartTime()throws Exception{
  TestTime clock=new TestTime();FetchBroker b=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String u){return new byte[]{1};}public void cancel(){}},clock);
  long before=clock.now();b.submit("one",false).get(2,TimeUnit.SECONDS);assertEquals(before+1000,WorkerPacing.ready(SessionState.prefs(c),0));assertEquals(0,SessionState.prefs(c).getLong("nextRequestAt",0));
 }

 @Test(timeout=10000) public void realisticTwentyFiveSecondDelayDoesNotSerializeFiveReadyWorkers()throws Exception{
  SessionState.prefs(c).edit().putInt("minimum",25).putInt("maximum",25).commit();TestTime clock=new TestTime();long before=clock.now();CountDownLatch five=new CountDownLatch(5),release=new CountDownLatch(1);List<Long> starts=Collections.synchronizedList(new ArrayList<>());
  FetchBroker b=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String u)throws Failure{starts.add(clock.now());five.countDown();try{release.await(3,TimeUnit.SECONDS);return new byte[]{1};}catch(InterruptedException e){throw new Failure("REQUEST_CANCELLED");}}public void cancel(){}},clock);b.limit=5;
  List<CompletableFuture<byte[]>>fs=new ArrayList<>();for(int n=0;n<5;n++)fs.add(b.submit("user"+n,false));assertTrue(five.await(2,TimeUnit.SECONDS));assertEquals(5,starts.size());for(long start:starts)assertEquals(before,start);for(int slot=0;slot<5;slot++)assertEquals(before+25000,WorkerPacing.ready(SessionState.prefs(c),slot));release.countDown();for(CompletableFuture<byte[]> future:fs)future.get(3,TimeUnit.SECONDS);
 }
 @Test public void workerReservationsPersistIndependentlyAndSchedulerUsesEarliest(){SharedPreferences p=SessionState.prefs(c);RunPolicy r=new RunPolicy(25,25,0,1439);WorkerPacing.reserve(p,0,100000,r);WorkerPacing.reserve(p,1,110000,r);assertEquals(125000,WorkerPacing.ready(p,0));assertEquals(135000,WorkerPacing.ready(p,1));assertEquals(125000,WorkerPacing.earliest(p,2));assertEquals(0,WorkerPacing.earliest(p,3));}

}
