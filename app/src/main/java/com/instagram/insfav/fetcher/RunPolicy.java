package com.instagram.insfav.fetcher;

import android.content.SharedPreferences;
import java.time.*;
import java.util.concurrent.ThreadLocalRandom;

final class RunPolicy {
    final int minimum, maximum, start, end;
    RunPolicy(int minimum, int maximum, int start, int end) {
        if (minimum < 1 || maximum < minimum || maximum > 86400 || start < 0 || start >= 1440 || end < 0 || end >= 1440 || start == end) throw new IllegalArgumentException("INVALID_SCHEDULE");
        this.minimum = minimum; this.maximum = maximum; this.start = start; this.end = end;
    }
    static RunPolicy read(SharedPreferences s) { return new RunPolicy(s.getInt("minimum", 15), s.getInt("maximum", 30), s.getInt("startMinute", 0), s.getInt("endMinute", 480)); }
    boolean contains(ZonedDateTime time) { int minute = time.getHour() * 60 + time.getMinute(); return start < end ? minute >= start && minute < end : minute >= start || minute < end; }
    long nextAllowed(long epoch, ZoneId zone) {
        ZonedDateTime now = Instant.ofEpochMilli(epoch).atZone(zone); if (contains(now)) return epoch; return nextWindow(epoch, zone);
    }
    long nextWindow(long epoch, ZoneId zone) {
        ZonedDateTime now = Instant.ofEpochMilli(epoch).atZone(zone);
        ZonedDateTime candidate = now.toLocalDate().atTime(start / 60, start % 60).atZone(zone);
        if (!candidate.isAfter(now)) candidate = now.toLocalDate().plusDays(1).atTime(start / 60, start % 60).atZone(zone);
        return candidate.toInstant().toEpochMilli();
    }
    long windowEnd(long epoch,ZoneId zone) {
        ZonedDateTime now=Instant.ofEpochMilli(epoch).atZone(zone);
        ZonedDateTime endAt=now.toLocalDate().atTime(end/60,end%60).atZone(zone);
        if(!endAt.isAfter(now))endAt=now.toLocalDate().plusDays(1).atTime(end/60,end%60).atZone(zone);
        return endAt.toInstant().toEpochMilli();
    }
    long delayMillis() { return ThreadLocalRandom.current().nextLong(minimum * 1000L, maximum * 1000L + 1); }
    static int minute(String text) {
        if (!text.matches("[0-2][0-9]:[0-5][0-9]")) throw new IllegalArgumentException("INVALID_TIME");
        int hour = Integer.parseInt(text.substring(0, 2)); if (hour > 23) throw new IllegalArgumentException("INVALID_TIME"); return hour * 60 + Integer.parseInt(text.substring(3));
    }
    static String time(int minutes) { return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60); }
}
