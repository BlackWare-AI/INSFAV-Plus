package com.instagram.insfav;
import org.json.*;import org.junit.Test;import org.junit.runner.RunWith;import org.robolectric.RobolectricTestRunner;import org.robolectric.annotation.Config;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class ResolutionAuditTest {
 @Test public void countsEveryCandidateIncludingDuplicatesAndUnmatchedNamesButKeepsUnknownSeparate()throws Exception {
  ThumbnailPolicy.Catalog c=new ThumbnailPolicy.Catalog();
  for(int[] wh:new int[][]{{256,256},{128,128},{512,512},{256,128},{0,0}})c.add(new ThumbnailPolicy.Candidate("same.webp","content://fixture/"+c.files,wh[0],wh[1],20,1,wh[0]>0));
  c.add(new ThumbnailPolicy.Candidate("unmatched name.png","content://fixture/unmatched",1024,1024,20,1,true));
  assertEquals(4,c.nonTargetResolution);assertEquals(1,c.unknownResolution);assertEquals(6,c.files);assertEquals(1,c.best.size());
  JSONObject index=new JSONObject().put("resolutionAudit",ResolutionAudit.result(c.nonTargetResolution,c.unknownResolution,true));
  assertEquals(4,ResolutionAudit.snapshot(index).getInt("count"));assertTrue(ResolutionAudit.snapshot(index).getBoolean("complete"));
 }
 @Test public void legacySavedDimensionsAreAvailableWithoutReopeningFilesAnd128IsStillUsable()throws Exception {
  JSONObject profiles=new JSONObject().put("legacy",new JSONObject().put("width",128).put("height",128).put("readable",true)).put("target",new JSONObject().put("width",256).put("height",256)).put("big",new JSONObject().put("width",512).put("height",512));
  JSONObject audit=ResolutionAudit.snapshot(new JSONObject().put("profiles",profiles));assertEquals(2,audit.getInt("count"));assertEquals("256×256",audit.getString("expected"));assertFalse(audit.getBoolean("complete"));assertTrue(profiles.getJSONObject("legacy").getBoolean("readable"));
 }
}
