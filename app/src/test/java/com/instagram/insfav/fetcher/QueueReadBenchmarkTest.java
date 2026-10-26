package com.instagram.insfav.fetcher;
import static org.junit.Assert.*;
import java.util.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class QueueReadBenchmarkTest {
 private long pointReads(QueueStore q) {try{return (Long)QueueStore.class.getDeclaredField("pointImageReads").get(q);}catch(ReflectiveOperationException baseline){return -1;}}
 @Test public void compareAggregatePlansAndSeedQueries() throws Exception {
  android.content.Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("fetcher-queue.db");
  try(QueueStore q=new QueueStore(context)) {
   SQLiteDatabase db=q.getWritableDatabase();db.beginTransaction();
   try(SQLiteStatement insert=db.compileStatement("INSERT INTO accounts(username,position,status,phase,attempts) VALUES (?,?,'RETRY_LATER','PENDING',1)")){
    for(int i=0;i<20000;i++){insert.bindString(1,"fixture_"+i);insert.bindLong(2,i);insert.executeInsert();}db.setTransactionSuccessful();
   } finally {db.endTransaction();}
   java.lang.reflect.Method summary=null;try{summary=QueueStore.class.getDeclaredMethod("summary");}catch(NoSuchMethodException baseline){}
   long[] old=new long[7],next=new long[7];
   for(int r=0;r<7;r++){
    long start=System.nanoTime();for(int n=0;n<20;n++){assertEquals(20000,(int)q.counts().get("RETRY_LATER"));q.phases();assertEquals(20000,q.held());q.recordedOutcomes();}old[r]=System.nanoTime()-start;
    if(summary!=null){start=System.nanoTime();for(int n=0;n<20;n++)summary.invoke(q);next[r]=System.nanoTime()-start;}
   }
   Arrays.sort(old);Arrays.sort(next);JSONArray plans=new JSONArray();
   for(String sql:new String[]{"SELECT status,COUNT(*) FROM accounts GROUP BY status","SELECT phase,COUNT(*) FROM accounts GROUP BY phase","SELECT status,phase,COUNT(*) FROM accounts GROUP BY status,phase","SELECT username,uri,name,size,width,height,modified,readable,needs_check FROM fetch_images"}){
    JSONArray details=new JSONArray();try(Cursor c=db.rawQuery("EXPLAIN QUERY PLAN "+sql,null)){while(c.moveToNext())details.put(c.getString(3));}plans.put(new JSONObject().put("sql",sql).put("plan",details));
   }
   System.out.println("QUEUE_READ "+new JSONObject().put("accounts",20000).put("repetitions",20).put("oldMedianNanos",old[3]).put("combinedMedianNanos",summary==null?JSONObject.NULL:next[3]).put("plans",plans));
  }
 }
 @Test public void seedReadsExistingImagesInBulkAndPreservesInvalidation() throws Exception {
  android.content.Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("fetcher-queue.db");
  JSONObject profiles=new JSONObject();List<String> users=new ArrayList<>();
  for(int i=0;i<5000;i++){String user="fixture_"+i;users.add(user);profiles.put(user,new JSONObject().put("uri","fixture:"+i).put("name",user+".webp").put("readable",true).put("size",200).put("width",256).put("height",256));}
  final long[] revision={1};Fetcher.initialize(context,new Fetcher.Host(){
   public Collection<String> users(){return users;}public String tree(){return "fixture";}public String directory(){return "root";}public long revision(){return revision[0];}public boolean hasImage(String u){return true;}public void imageWritten(String u,android.net.Uri uri,String tree){}
   public JSONObject catalog()throws Exception{return new JSONObject().put("treeUri","fixture").put("profiles",profiles);}
  });
  try(QueueStore q=new QueueStore(context)){
   assertTrue(FetcherCatalog.seed(q));long before=pointReads(q);revision[0]++;long start=System.nanoTime();assertTrue(FetcherCatalog.seed(q));long elapsed=System.nanoTime()-start;
   long after=pointReads(q),reads=before<0?-1:after-before;assertNotNull(q.image("fixture_1"));
   q.invalidateUri("fixture:1");revision[0]++;assertTrue(FetcherCatalog.seed(q));assertFalse(q.image("fixture_1").needsCheck);
   profiles.remove("fixture_2");revision[0]++;assertTrue(FetcherCatalog.seed(q));assertEquals("",q.image("fixture_2").uri);
   System.out.println("CATALOG_SEED "+new JSONObject().put("profiles",5000).put("unchangedSeedNanos",elapsed).put("pointReads",reads));
  }
 }
}
