package com.instagram.insfav;
import static org.junit.Assert.*;
import android.content.*;
import android.net.Uri;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class InventoryPageBenchmarkTest {
 @Test public void everyPageKeepsItsOrderTotalAndReadOnlyBoundary() throws Exception {
  for(int count:new int[]{5000,15000,20000}){
   LocalImagesTest harness=new LocalImagesTest();harness.setup();
   Store store=ReflectionHelpers.getField(harness,"store");LocalImages images=ReflectionHelpers.getField(harness,"images");Context context=RuntimeEnvironment.getApplication();
   String tree="content://insfav.test/tree/root";images.select(Uri.parse(tree),Intent.FLAG_GRANT_READ_URI_PERMISSION);
   JSONArray items=new JSONArray();JSONObject profiles=new JSONObject();
   for(int i=0;i<count;i++){String user="fixture_"+i;items.put(new JSONObject().put("pageId",user));profiles.put(user,new JSONObject().put("readable",true).put("uri","content://insfav.test/document/root%2FExample.JPG").put("width",256).put("height",256));}
   store.importDocument(new JSONArray().put(new JSONObject().put("name","Synthetic").put("items",items)).toString());store.replaceIndex(new JSONObject().put("treeUri",tree).put("profiles",profiles));
   try(ThumbnailInventory inventory=new ThumbnailInventory(context,store,images)){
    long start=System.nanoTime();int pages=(count+99)/100;
    for(int page=0;page<pages;page++){
     JSONObject result=inventory.query("present",page*100,"");assertEquals(count,result.getInt("total"));assertTrue(result.getBoolean("readOnly"));
     assertEquals("fixture_"+(page*100),result.getJSONArray("items").getJSONObject(0).getString("user"));
    }
    long elapsed=System.nanoTime()-start,passes=-1;try{passes=(Long)ThumbnailInventory.class.getDeclaredField("catalogPasses").get(inventory);}catch(NoSuchFieldException baseline){}
    System.out.println("INVENTORY_PAGES "+new JSONObject().put("accounts",count).put("pages",pages).put("elapsedNanos",elapsed).put("catalogPasses",passes));
    if(passes>=0)assertEquals(1,passes);
   }
  }
 }
}
