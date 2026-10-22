package com.instagram.insfav.fetcher;
import android.content.SharedPreferences;
import java.time.ZonedDateTime;
final class ScheduleStatus {
 static String text(SharedPreferences p,boolean running,int active) {
  if(!p.getBoolean("armed",false)||p.getBoolean("userPaused",true))return "Stopped — User Disabled";
  if("RATE_LIMITED".equals(p.getString("blocked","")))return "Rate Limited — Cooldown";
  if(!p.getString("blocked", "").isEmpty()&&!"SYSTEM_RESTRICTED".equals(p.getString("blocked","")))return "Paused — "+p.getString("blocked", "");
  if(!RunPolicy.read(p).contains(ZonedDateTime.now()))return "Paused — Outside Schedule"+(active>0?" · Finishing active profiles":"");
  if("SYSTEM_RESTRICTED".equals(p.getString("blocked",""))||p.getLong("systemNotBefore",0)>System.currentTimeMillis())return "Waiting — Android Background Restrictions";
  if(running)return "Working — Within Schedule";
  return "Waiting — Android Background Restrictions";
 }
 private ScheduleStatus(){}
}
