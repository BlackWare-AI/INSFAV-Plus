package com.instagram.insfav.fetcher;
import android.content.*;import android.content.pm.ProviderInfo;import android.net.Uri;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import java.io.*;import java.nio.file.Files;import java.util.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IntegratedEngineTest{
 @Test(timeout=30000) public void unavailableAndAmbiguousProfilesCannotPreventFollowingSuccess()throws Exception{
  Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("fetcher-queue.db");QueueControl.requested=false;
  SessionState.prefs(c).edit().clear().putInt("minimum",1).putInt("maximum",1).putInt("startMinute",0).putInt("endMinute",1439).putString("tree","content://fetcher.test/tree/root").putString("directoryId","root/.thumbnails").commit();
  ProviderInfo info=new ProviderInfo();info.authority="fetcher.test";info.exported=true;info.grantUriPermissions=true;info.readPermission="android.permission.MANAGE_DOCUMENTS";info.writePermission=info.readPermission;
  FetcherStorageTest.Fixture provider=Robolectric.buildContentProvider(FetcherStorageTest.Fixture.class).create(info).get();provider.root=Files.createTempDirectory("integration-engine").toFile();new File(provider.root,".thumbnails").mkdir();Uri tree=Uri.parse("content://fetcher.test/tree/root");c.getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  List<String> requests=Collections.synchronizedList(new ArrayList<>());Set<String> written=new HashSet<>();byte[] png=CodecTest.png(640,480);
  Fetcher.initialize(c,new Fetcher.Host(){public Collection<String> users(){return Arrays.asList("gone","ambiguous","good");}public String tree(){return tree.toString();}public String directory(){return "root/.thumbnails";}public long revision(){return 0;}public boolean hasImage(String u){return written.contains(u);}public void imageWritten(String u,Uri uri,String t){written.add(u);}});
  FetchBroker broker=new FetchBroker(c,full->new ProfileProvider(){public void authenticate(){} public byte[] fetch(String user)throws Failure{requests.add(user);if(user.equals("gone"))throw new Failure("PROFILE_NOT_FOUND_OR_UNAVAILABLE",404,0,false,true);if(user.equals("ambiguous"))throw new Failure("PROFILE_IMAGE_URL_MISSING",200,0,false);return png.clone();}public void cancel(){}},new TestTime());
  org.robolectric.util.ReflectionHelpers.setStaticField(FetchBroker.class,"instance",broker);
  try(QueueStore q=new QueueStore(c)){q.sync(Arrays.asList("gone","ambiguous","good"));}
  new QueueEngine(c,state->{},()->false).run("missing",false);
  assertEquals(Arrays.asList("gone","ambiguous","good"),requests);assertEquals(Collections.singleton("good"),written);
  try(QueueStore q=new QueueStore(c)){assertEquals(Integer.valueOf(1),q.counts().get("SUCCESS"));assertEquals(Integer.valueOf(1),q.counts().get("SKIPPED_UNAVAILABLE"));assertNull(q.counts().get("SKIPPED_NO_IMAGE"));assertEquals(Integer.valueOf(1),q.counts().get("RETRY_LATER"));assertEquals(1,q.attempts("ambiguous"));assertTrue(q.pending().isEmpty());}
  assertTrue(ThumbnailCodec.valid(Files.readAllBytes(new File(provider.root,".thumbnails/good.webp").toPath())));
 }
 @Test(timeout=30000) public void globalRateLimitPausesWithoutConsumingTheAccountRetryBudget()throws Exception{
  Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("fetcher-queue.db");QueueControl.requested=false;
  SessionState.prefs(c).edit().clear().putInt("minimum",1).putInt("maximum",1).putInt("startMinute",0).putInt("endMinute",1439).putString("tree","content://fetcher.test/tree/root").putString("directoryId","root/.thumbnails").commit();
  ProviderInfo info=new ProviderInfo();info.authority="fetcher.test";info.exported=true;info.grantUriPermissions=true;info.readPermission="android.permission.MANAGE_DOCUMENTS";info.writePermission=info.readPermission;
  FetcherStorageTest.Fixture provider=Robolectric.buildContentProvider(FetcherStorageTest.Fixture.class).create(info).get();provider.root=Files.createTempDirectory("integration-engine").toFile();new File(provider.root,".thumbnails").mkdir();Uri tree=Uri.parse("content://fetcher.test/tree/root");c.getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

  Fetcher.initialize(c,new Fetcher.Host(){public Collection<String> users(){return Arrays.asList("limited");}public String tree(){return tree.toString();}public String directory(){return "root/.thumbnails";}public long revision(){return 0;}public boolean hasImage(String u){return false;}public void imageWritten(String u,Uri uri,String t){}});
  java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();byte[] resumed=CodecTest.png(640,480);
  FetchBroker broker=new FetchBroker(c,full->new ProfileProvider(){public void authenticate(){} public byte[] fetch(String user)throws Failure{if(calls.incrementAndGet()==1)throw new Failure("RATE_LIMITED",429,System.currentTimeMillis()+60000,true);return resumed.clone();}public void cancel(){}},new TestTime());org.robolectric.util.ReflectionHelpers.setStaticField(FetchBroker.class,"instance",broker);
  try(QueueStore q=new QueueStore(c)){q.sync(Arrays.asList("limited"));}new QueueEngine(c,state->{},()->false).run("resume",false);
  try(QueueStore q=new QueueStore(c)){assertEquals(0,q.attempts("limited"));assertEquals(Integer.valueOf(1),q.counts().get("RETRY_LATER"));assertEquals(Arrays.asList("limited"),q.pending());}assertEquals("RATE_LIMITED",SessionState.prefs(c).getString("blocked",""));
  int enumerations=provider.childQueries;new QueueEngine(c,state->{},()->false).run("resume",false);assertEquals(1,calls.get());assertEquals(enumerations,provider.childQueries);
  SessionState.prefs(c).edit().putLong("retryAt",0).commit();new QueueEngine(c,state->{},()->false).run("resume",false);assertEquals(2,calls.get());try(QueueStore q=new QueueStore(c)){assertEquals(Integer.valueOf(1),q.counts().get("SUCCESS"));assertEquals(0,q.attempts("limited"));}
 }

 @Test(timeout=30000) public void cachedScheduledWorkIsBoundedWithoutNetworkOrUiFlood()throws Exception{
  Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("fetcher-queue.db");QueueControl.requested=false;
  SessionState.prefs(c).edit().clear().putInt("minimum",1).putInt("maximum",1).putInt("startMinute",0).putInt("endMinute",1439).putString("tree","content://fetcher.test/tree/root").putString("directoryId","root/.thumbnails").commit();
  ProviderInfo info=new ProviderInfo();info.authority="fetcher.test";info.exported=true;info.grantUriPermissions=true;info.readPermission="android.permission.MANAGE_DOCUMENTS";info.writePermission=info.readPermission;
  FetcherStorageTest.Fixture provider=Robolectric.buildContentProvider(FetcherStorageTest.Fixture.class).create(info).get();provider.root=Files.createTempDirectory("integration-engine").toFile();new File(provider.root,".thumbnails").mkdir();Uri tree=Uri.parse("content://fetcher.test/tree/root");c.getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

  List<String> names=new ArrayList<>();for(int i=0;i<1000;i++)names.add("cached"+i);
  Fetcher.initialize(c,new Fetcher.Host(){public Collection<String> users(){return names;}public String tree(){return tree.toString();}public String directory(){return "root/.thumbnails";}public long revision(){return 0;}public org.json.JSONObject catalog()throws Exception{org.json.JSONObject images=new org.json.JSONObject();for(String u:names)images.put(u,new org.json.JSONObject().put("uri","content://fetcher.test/tree/root/document/root%2F.thumbnails%2F"+u+".webp").put("name",u+".webp").put("width",256).put("height",256).put("readable",true));return new org.json.JSONObject().put("treeUri",tree.toString()).put("profiles",images);}public boolean hasImage(String u){throw new AssertionError("No per-account host validation on warm start");}public void imageWritten(String u,Uri uri,String t){}});
  try(QueueStore q=new QueueStore(c)){q.sync(names);}
  java.util.concurrent.atomic.AtomicInteger notices=new java.util.concurrent.atomic.AtomicInteger();new QueueEngine(c,state->notices.incrementAndGet(),()->false).run("scheduled",true);
  try(QueueStore q=new QueueStore(c)){assertEquals(Integer.valueOf(1000),q.counts().get("EXISTING"));assertTrue(q.pending().isEmpty());assertNull(q.next());}assertTrue(notices.get()<=5);
 }

 @Test(timeout=30000) public void coordinatorRunsFiveDistinctAccountsInParallelAndCommitsEachOnce()throws Exception{
  Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("fetcher-queue.db");QueueControl.requested=false;
  SessionState.prefs(c).edit().clear().putInt("minimum",1).putInt("maximum",1).putInt("startMinute",0).putInt("endMinute",1439).putInt("concurrentWebViews",5).putString("tree","content://fetcher.test/tree/root").putString("directoryId","root/.thumbnails").commit();
  ProviderInfo info=new ProviderInfo();info.authority="fetcher.test";info.exported=true;info.grantUriPermissions=true;info.readPermission="android.permission.MANAGE_DOCUMENTS";info.writePermission=info.readPermission;
  FetcherStorageTest.Fixture provider=Robolectric.buildContentProvider(FetcherStorageTest.Fixture.class).create(info).get();provider.root=Files.createTempDirectory("parallel-engine").toFile();new File(provider.root,".thumbnails").mkdir();Uri tree=Uri.parse("content://fetcher.test/tree/root");c.getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  List<String> users=Arrays.asList("one","two","three","four","five");Set<String>written=java.util.concurrent.ConcurrentHashMap.newKeySet();java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(5);byte[] png=CodecTest.png(640,480);
  Fetcher.initialize(c,new Fetcher.Host(){public Collection<String> users(){return users;}public String tree(){return tree.toString();}public String directory(){return "root/.thumbnails";}public long revision(){return 0;}public boolean hasImage(String u){return written.contains(u);}public void imageWritten(String u,Uri uri,String tr){assertTrue(written.add(u));}});
  FetchBroker b=new FetchBroker(c,full->new ProfileProvider(){public void authenticate(){} public byte[] fetch(String user)throws Failure{entered.countDown();try{if(!entered.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new Failure("TEST_SERIALIZED");return png.clone();}catch(InterruptedException e){throw new Failure("REQUEST_CANCELLED");}}public void cancel(){}},new TestTime(),n->n);org.robolectric.util.ReflectionHelpers.setStaticField(FetchBroker.class,"instance",b);
  try(QueueStore q=new QueueStore(c)){q.sync(users);}new QueueEngine(c,state->{},()->false).run("missing",false);
  assertEquals(5,written.size());assertEquals(5,QueueControl.peakAccounts);try(QueueStore q=new QueueStore(c)){assertEquals(Integer.valueOf(5),q.counts().get("SUCCESS"));assertTrue(q.pending().isEmpty());}
 }

}
