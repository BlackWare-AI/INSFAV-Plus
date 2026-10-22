package com.instagram.insfav.fetcher;
import java.time.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ScheduleBoundaryTest {
 @Test public void everyMinuteUsesLocal24HourHalfOpenWindow(){for(int minute=0;minute<1440;minute++){
  ZonedDateTime time=LocalDate.of(2026,10,21).atStartOfDay(ZoneId.of("Europe/Istanbul")).plusMinutes(minute);
  assertEquals(minute>=540&&minute<1020,new RunPolicy(1,1,540,1020).contains(time));
  assertEquals(minute>=1320||minute<360,new RunPolicy(1,1,1320,360).contains(time));
  assertEquals(minute,RunPolicy.minute(RunPolicy.time(minute)));
 }}
 @Test public void timezoneChangeReevaluatesSameInstant(){Instant instant=Instant.parse("2026-10-21T06:00:00Z");RunPolicy r=new RunPolicy(1,1,540,1020);assertTrue(r.contains(instant.atZone(ZoneId.of("Europe/Istanbul"))));assertFalse(r.contains(instant.atZone(ZoneId.of("UTC"))));}
 @Test public void nextWindowAfterRebootUsesCurrentZoneAndSupportsMidnight(){RunPolicy r=new RunPolicy(1,1,1320,360);ZoneId zone=ZoneId.of("Europe/Istanbul");long epoch=ZonedDateTime.of(2026,10,21,6,0,0,0,zone).toInstant().toEpochMilli();assertEquals(22,Instant.ofEpochMilli(r.nextAllowed(epoch,zone)).atZone(zone).getHour());}
 @Test public void invalid24HourInputsAreRejected(){for(String time:new String[]{"24:00","23:60","9:00","09:00 PM","-1:00"})try{RunPolicy.minute(time);fail(time);}catch(IllegalArgumentException expected){}}
}
