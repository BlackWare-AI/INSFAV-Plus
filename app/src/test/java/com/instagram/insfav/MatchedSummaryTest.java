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
 @Test public void failedInPlaceSnapshotStillInvalidatesCountsAndProfileDeltas() throws Exception {
  java.io.File isolatedRoot=java.nio.file.Files.createTempDirectory("index-write-failure").toFile();
  android.content.Context isolated=new android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
    @Override public java.io.File getFilesDir(){return isolatedRoot;}
  };
  Store store=new Store(isolated);store.settings.put("treeUri","fixture");store.saveSettings();
  store.importDocument("[{\"name\":\"Synthetic\",\"items\":[{\"pageId\":\"one\"}]}]");
  store.replaceIndex(new JSONObject().put("revision",1).put("profiles",new JSONObject().put("one",new JSONObject().put("readable",true))));
  MatchedSummary summary=new MatchedSummary();assertEquals(1,summary.count(store,true));long previous=store.index.optLong("revision");
  store.index.getJSONObject("profiles").getJSONObject("one").put("readable",false);
  java.io.File target=new java.io.File(store.root,"thumbnail-index.json");assertTrue(target.delete());
  for(String suffix:new String[]{"",".bak",".new"}) {
    java.nio.file.Path blocked=new java.io.File(store.root,"thumbnail-index.json"+suffix).toPath();java.nio.file.Files.createDirectories(blocked);java.nio.file.Files.write(blocked.resolve("blocked"),new byte[]{1});
  }
  assertThrows(java.io.IOException.class,()->store.replaceIndex(store.index));
  assertEquals(0,summary.count(store,true));assertNull(store.changesSince(previous));
 }
}
