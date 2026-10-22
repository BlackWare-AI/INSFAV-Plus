package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class PolicyTest {
  @Test
  public void sameDayAndOvernightBoundariesAreEndExclusive() {
    RunPolicy normal = new RunPolicy(15, 30, 120, 420), overnight = new RunPolicy(1, 1, 1320, 120);
    assertTrue(normal.contains(ZonedDateTime.parse("2026-10-20T02:00:00+03:00")));
    assertFalse(normal.contains(ZonedDateTime.parse("2026-10-20T07:00:00+03:00")));
    assertTrue(overnight.contains(ZonedDateTime.parse("2026-10-20T23:00:00+03:00")));
    assertTrue(overnight.contains(ZonedDateTime.parse("2026-10-20T01:59:59+03:00")));
    assertFalse(overnight.contains(ZonedDateTime.parse("2026-10-20T02:00:00+03:00")));
  }

  @Test
  public void nextWindowUsesLocalCalendarAndSurvivesMidnight() {
    RunPolicy policy = new RunPolicy(15, 30, 1320, 120);
    ZoneId zone = ZoneId.of("Europe/Istanbul");
    long now = ZonedDateTime.of(2026, 10, 20, 3, 0, 0, 0, zone).toInstant().toEpochMilli();
    assertEquals(
        ZonedDateTime.of(2026, 10, 20, 22, 0, 0, 0, zone).toInstant().toEpochMilli(),
        policy.nextAllowed(now, zone));
    long inside = ZonedDateTime.of(2026, 10, 20, 23, 0, 0, 0, zone).toInstant().toEpochMilli();
    assertEquals(inside, policy.nextAllowed(inside, zone));
    assertTrue(policy.nextWindow(inside, zone) > inside);
  }

  @Test
  public void delayBoundsAndValidation() {
    RunPolicy policy = new RunPolicy(2, 4, 0, 60);
    for (int i = 0; i < 1000; i++) {
      long ms = policy.delayMillis();
      assertTrue(ms >= 2000 && ms <= 4000);
    }
    assertEquals(1000, new RunPolicy(1, 1, 0, 60).delayMillis());
    for (String text : new String[] {"24:00", "2:00", "12:60", "aa:bb"})
      try {
        RunPolicy.minute(text);
        fail();
      } catch (IllegalArgumentException expected) {
      }
    try {
      new RunPolicy(30, 15, 0, 60);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      new RunPolicy(15, 30, 60, 60);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void autoResumeNeedsExplicitArmBackgroundAndVerifiedSession() {
    android.content.SharedPreferences p = SessionState.prefs(RuntimeEnvironment.getApplication());
    p.edit().clear().commit();
    assertFalse(WorkScheduler.enabled(p));
    p.edit()
        .putBoolean("background", true)
        .putBoolean("armed", true)
        .putBoolean("userPaused", false)
        .commit();
    assertTrue(WorkScheduler.enabled(p));
    p.edit().putString("blocked", "RATE_LIMITED").commit();
    assertTrue(WorkScheduler.enabled(p));
    for (String blocked :
        new String[] {"AUTH_REQUIRED", "CHALLENGE_REQUIRED", "PROCESSING_ERROR"}) {
      p.edit().putString("blocked", blocked).commit();
      assertFalse(WorkScheduler.enabled(p));
    }
    p.edit().putString("blocked", "").putBoolean("userPaused", true).commit();
    assertFalse(WorkScheduler.enabled(p));
  }
}
