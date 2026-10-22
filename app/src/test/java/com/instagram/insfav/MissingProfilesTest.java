package com.instagram.insfav;
import android.content.*;import org.json.*;import org.junit.Test;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;
import java.util.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk={28,34},manifest=Config.NONE)
public class MissingProfilesTest {
 @Test public void copiesOnlyUniqueMissingUsernamesInListOrderToRealAndroidClipboard()throws Exception {
  JSONObject images=new JSONObject().put("present",new JSONObject().put("readable",true)).put("broken",new JSONObject().put("readable",false));
  String text=MissingProfiles.text(Arrays.asList("@Yelissimm","erdigulll","natarix","PRESENT","NATARIX","broken","https://example.test/","Folder / Account"),images,true);
  assertEquals("yelissimm\nerdigulll\nnatarix\nbroken",text);
  Context c=RuntimeEnvironment.getApplication();assertEquals(4,MissingProfiles.copy(c,text));ClipData clip=c.getSystemService(ClipboardManager.class).getPrimaryClip();
  assertNotNull(clip);assertEquals(1,clip.getItemCount());assertEquals(text,clip.getItemAt(0).getText().toString());assertNull(clip.getItemAt(0).getUri());
 }
 @Test public void inaccessibleFolderMarksUsersMissingAndEmptyResultDoesNotEraseClipboard()throws Exception {
  JSONObject images=new JSONObject().put("present",new JSONObject().put("readable",true));assertEquals("present",MissingProfiles.text(Arrays.asList("present"),images,false));
  Context c=RuntimeEnvironment.getApplication();MissingProfiles.copy(c,"retained");assertEquals(0,MissingProfiles.copy(c,""));assertEquals("retained",c.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString());
  assertEquals("",MissingProfiles.text(Arrays.asList("present"),images,true));
 }
}
