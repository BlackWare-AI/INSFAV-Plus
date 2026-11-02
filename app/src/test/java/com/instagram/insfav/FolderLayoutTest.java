package com.instagram.insfav;
import static org.junit.Assert.*;
import android.content.Context;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class FolderLayoutTest {
 Context c;
 @Before public void setup(){c=RuntimeEnvironment.getApplication();}
 @Test public void archiveRoundTripsEveryModeWithoutChangingAccountLayout()throws Exception{
  // The two apps deliberately keep their own storage names.
  for(String name:new String[]{"plus-ui","insfav-ui"})c.getSharedPreferences(name,0).edit().putInt("columns",4).commit();
  for(int n:new int[]{0,2,3,4,5}){FolderLayout.save(c,n);JSONObject archive=new JSONObject(FolderLayout.append(c,new JSONObject()).toString());FolderLayout.save(c,n==0?2:0);FolderLayout.restore(c,archive);assertEquals(n,FolderLayout.choice(c));}
  for(String name:new String[]{"plus-ui","insfav-ui"})assertEquals(4,c.getSharedPreferences(name,0).getInt("columns",0));
 }
 @Test public void oldArchivesPreserveChoiceAndMalformedMetadataCannotPartiallyApply()throws Exception{
  FolderLayout.save(c,3);FolderLayout.restore(c,new JSONObject());assertEquals(3,FolderLayout.choice(c));
  for(Object invalid:new Object[]{1,6,-1,2.5,"3",true,JSONObject.NULL,4294967298L}){
   try{FolderLayout.restore(c,new JSONObject().put("folderColumns",invalid));fail();}catch(java.io.IOException expected){}
   assertEquals(3,FolderLayout.choice(c));
  }
 }
}
