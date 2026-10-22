package com.instagram.insfav.fetcher;

import java.time.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScheduleExplanationTest {
 @Test public void configuredWindowAndNextLocalTimeUse24HourFormat(){
  ZoneId zone=ZoneId.of("Europe/Istanbul");long now=ZonedDateTime.of(2026,10,22,13,0,0,0,zone).toInstant().toEpochMilli();
  String text=ScheduleStatus.outsideMessage(new RunPolicy(1,1,480,720),now,zone);
  assertTrue(text.contains("08:00–12:00"));assertTrue(text.contains("23.10.2026 08:00"));
 }
 @Test public void overnightWindowNextStartUsesCurrentLocalDate(){
  ZoneId zone=ZoneId.of("Europe/Istanbul");long now=ZonedDateTime.of(2026,10,22,8,0,0,0,zone).toInstant().toEpochMilli();
  assertTrue(ScheduleStatus.outsideMessage(new RunPolicy(1,1,1320,360),now,zone).contains("22.10.2026 22:00"));
 }
 @Test public void daylightSavingBoundaryRemainsInsideTheConfiguredLocalWindow(){
  RunPolicy p=new RunPolicy(1,1,60,240);ZoneId zone=ZoneId.of("Europe/Berlin");
  for(String instant:new String[]{"2026-10-25T00:30:00Z","2026-10-25T01:30:00Z"})assertTrue(p.contains(Instant.parse(instant).atZone(zone)));
 }
}
