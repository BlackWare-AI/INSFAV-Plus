package com.instagram.insfav;
import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class JournalLifecycleTest {
 @Test public void oneBatchOwnsOneHelperAndFlushOrRecordFailureReleasesIt() throws Exception {
  Store store=new Store(RuntimeEnvironment.getApplication());store.settings.put("treeUri","fixture");store.saveSettings();store.replaceIndex(new JSONObject());
  store.updateProfile("one",new JSONObject().put("name","one.webp").put("readable",true),"fixture");
  Object helper=ReflectionHelpers.getField(store,"pendingJournal");assertNotNull(helper);
  store.updateProfile("two",new JSONObject().put("name","two.webp").put("readable",true),"fixture");assertSame(helper,ReflectionHelpers.getField(store,"pendingJournal"));
  store.flushIndex();assertNull(ReflectionHelpers.getField(store,"pendingJournal"));
  assertThrows(IllegalArgumentException.class,()->store.updateProfile("invalid user",new JSONObject(),"fixture"));assertNull(ReflectionHelpers.getField(store,"pendingJournal"));
 }
}
