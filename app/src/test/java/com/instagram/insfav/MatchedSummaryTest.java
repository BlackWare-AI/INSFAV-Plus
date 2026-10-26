package com.instagram.insfav;
import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE)
public class MatchedSummaryTest {
 @Test public void reusesCountsAndInvalidatesImportClearProfileReplacementAndAccess() throws Exception {
  Store store=new Store(RuntimeEnvironment.getApplication());
  store.settings.put("treeUri","fixture");store.saveSettings();
  store.importDocument("[{\"name\":\"Synthetic\",\"items\":[{\"pageId\":\"one\"},{\"pageId\":\"one\"},{\"pageId\":\"two\"}]}]");
  store.replaceIndex(new JSONObject().put("treeUri","fixture").put("profiles",new JSONObject().put("one",new JSONObject().put("readable",true))));
  MatchedSummary summary=new MatchedSummary();
  assertEquals(1,summary.count(store,true));long visited=summary.visited;
  for(int i=0;i<100;i++)assertEquals(1,summary.count(store,true));
  assertEquals(visited,summary.visited);
  assertEquals(0,summary.count(store,false));assertEquals(1,summary.count(store,true));
  store.updateProfile("two",new JSONObject().put("name","two.webp").put("readable",true),"fixture");
  assertEquals(2,summary.count(store,true));
  store.importDocument("[{\"name\":\"Next\",\"items\":[{\"pageId\":\"two\"}]}]");
  assertEquals(1,summary.count(store,true));
  // Replace with an equal public revision: cache must still invalidate.
  store.replaceIndex(new JSONObject().put("revision",store.index.optLong("revision")));
  assertEquals(0,summary.count(store,true));
  store.clearDocument();assertEquals(0,summary.count(store,true));
 }
 @Test public void unchangedSettingsDoNotReplaceTheAtomicFile() throws Exception {
  Store store=new Store(RuntimeEnvironment.getApplication());store.settings.put("fixture",true);store.saveSettings();
  java.io.File file=new java.io.File(store.root,"image-settings.json");assertTrue(file.setLastModified(1000));
  store.saveSettings();assertEquals(1000,file.lastModified());
  store.settings.put("fixture",false);store.saveSettings();assertNotEquals(1000,file.lastModified());
 }
}
