package com.instagram.insfav;
import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE)
public class NativeStatusBenchmarkTest {
 @Test public void repeatedSmallSnapshotsAtRequiredSizes() throws Exception {
  for(int count:new int[]{5000,15000,20000}) {
   Store store=new Store(RuntimeEnvironment.getApplication());store.settings.put("treeUri","fixture");store.saveSettings();
   JSONArray items=new JSONArray();JSONObject profiles=new JSONObject();
   for(int i=0;i<count;i++){String user="fixture_"+i;items.put(new JSONObject().put("pageId",user));profiles.put(user,new JSONObject().put("readable",true).put("uri","fixture:"+i).put("name",user+".webp").put("width",256).put("height",256));}
   store.importDocument(new JSONArray().put(new JSONObject().put("name","Synthetic").put("items",items)).toString());
   store.replaceIndex(new JSONObject().put("treeUri","fixture").put("revision",1).put("profiles",profiles));
   LocalImages images=new LocalImages(RuntimeEnvironment.getApplication(),store);ReflectionHelpers.setField(images,"accessible",true);
   images.snapshot(false);long[] samples=new long[5];
   for(int r=0;r<samples.length;r++){long began=System.nanoTime();for(int n=0;n<100;n++)assertEquals(count,images.snapshot(false).getInt("matched"));samples[r]=System.nanoTime()-began;}
   java.util.Arrays.sort(samples);
   long started=System.nanoTime();Store reopened=new Store(RuntimeEnvironment.getApplication());long reopen=System.nanoTime()-started;assertEquals(count,reopened.index.getJSONObject("profiles").length());
   System.out.println("SMALL_STATUS "+new JSONObject().put("accounts",count).put("responses",100).put("medianNanos",samples[2]).put("warmStoreReopenNanos",reopen));
  }
 }
}
