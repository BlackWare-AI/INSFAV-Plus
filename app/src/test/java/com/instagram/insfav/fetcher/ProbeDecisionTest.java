package com.instagram.insfav.fetcher;
import org.json.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class ProbeDecisionTest {
 JSONObject absent()throws Exception{return new JSONObject().put("wall","PROFILE_PAGE_UNAVAILABLE").put("pageReady",true).put("searchCompleted",true).put("unavailableText",true).put("routedError",true);}
 @Test public void repeatedRoutedErrorsWithoutExplicitTextRemainRetryable()throws Exception{
  for(int i=0;i<100;i++){ProfileProvider.Failure e=ProbeDecision.failure(absent().put("unavailableText",false),"example",200,true,false);assertFalse(FailurePolicy.unavailable(e));assertTrue(FailurePolicy.retry(e));assertFalse(FailurePolicy.pause(e));}
 }
 @Test public void completedSearchAndNoHistoricalCandidateAreRequired()throws Exception{
  for(int http:new int[]{0,200,404})for(boolean verified:new boolean[]{false,true}){
   ProfileProvider.Failure e=ProbeDecision.failure(absent(),"example",http,verified,false);
   assertTrue(e.confirmedUnavailable);assertTrue(FailurePolicy.unavailable(e));assertFalse(FailurePolicy.retry(e));assertFalse(FailurePolicy.pause(e));
  }
  assertNull(ProbeDecision.failure(absent().put("searchCompleted",false),"example",200,true,false));
  assertNull(ProbeDecision.failure(absent(),"example",200,true,true));
  assertFalse(FailurePolicy.unavailable(ProbeDecision.failure(absent(),"",200,false,false)));
  assertFalse(FailurePolicy.unavailable(ProbeDecision.failure(absent().put("unavailableText",false),"example",404,true,false)));
  assertFalse(FailurePolicy.unavailable(new ProfileProvider.Failure("PROFILE_NOT_FOUND_OR_UNAVAILABLE",404,0,false)));
 }
 @Test public void currentImageCandidateVetoesFastSkip()throws Exception{
  assertNull(ProbeDecision.failure(absent().put("candidateFound",true),"example",404,true,true));
 }
 @Test public void deadlineIsRetryableTimeoutEvenAfterPageFinished(){assertNull(ProbeDecision.deadline(14999,15000));ProfileProvider.Failure e=ProbeDecision.deadline(15000,15000);assertEquals("WEBVIEW_TIMEOUT",e.code);assertTrue(FailurePolicy.retry(e));assertFalse(FailurePolicy.noImage(e));assertFalse(FailurePolicy.pause(e));}
 @Test public void ambiguityAndIdentityAreNeverPermanentNoImage(){for(String code:new String[]{"PROFILE_DATA_AMBIGUOUS","PROFILE_IDENTITY_UNVERIFIED","PROFILE_IMAGE_URL_MISSING"}){ProfileProvider.Failure e=new ProfileProvider.Failure(code);assertTrue(code,FailurePolicy.retry(e));assertFalse(FailurePolicy.noImage(e));}}
 @Test public void defaultAvatarNeedsExplicitIdentityAndCompleteSearch()throws Exception{
  JSONObject data=new JSONObject().put("wall","PROFILE_DEFAULT_AVATAR");assertNull(ProbeDecision.failure(data,"example",0,true,false));
  data.put("identityConfirmed",true).put("pageReady",true).put("searchCompleted",true);assertTrue(FailurePolicy.noImage(ProbeDecision.failure(data,"example",0,true,false)));assertNull(ProbeDecision.failure(data,"example",0,true,true));
 }
}
