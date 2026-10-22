package com.instagram.insfav.fetcher;
import android.content.SharedPreferences;
import org.json.*;
/** Safe live status; account identifiers stay out of the progress card and diagnostics. */
final class FetchProgress {
 static JSONObject snapshot(SharedPreferences prefs,boolean running,int active,int queued,boolean authenticating,long next,long now)throws JSONException {
  String state=prefs.getString("state","Idle"),blocked=prefs.getString("blocked","");
  long wait=0;String phase=state;
  if(running&&blocked.isEmpty()){
   if(authenticating)phase="Verifying Instagram session";
   else if(active>0)phase="Loading profiles / downloading images";
   else if(queued>0&&next>now){phase="Waiting for configured worker delay";wait=(next-now+999)/1000;}
   else if(queued>0)phase="Preparing next profiles";
  }
  return new JSONObject().put("phase",phase).put("active",Math.max(0,active)).put("queued",Math.max(0,queued))
   .put("waitSeconds",wait).put("handledThisRun",QueueControl.runProcessed).put("lastResult",QueueControl.lastResult);
 }
 private FetchProgress(){}
}
