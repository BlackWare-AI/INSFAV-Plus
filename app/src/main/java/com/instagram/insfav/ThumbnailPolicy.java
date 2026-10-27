package com.instagram.insfav;

import java.util.*;

/** Filename identity and deterministic candidate selection; no Android or filesystem access. */
final class ThumbnailPolicy {
  private static final java.util.regex.Pattern ACCOUNT = java.util.regex.Pattern.compile("[a-z0-9._]{1,30}");
  private static final java.util.regex.Pattern PREFIX = java.util.regex.Pattern.compile("^@");
  private static final java.util.regex.Pattern IMAGE = java.util.regex.Pattern.compile(".*\\.(webp|jpe?g|png)$");
  static String normalize(String value) {
    return value == null ? "" : PREFIX.matcher(value.trim()).replaceFirst("").toLowerCase(Locale.ROOT);
  }

  static String username(String filename) {
    int dot = filename.lastIndexOf('.');
    if (dot <= 0 || !supported(filename)) return "";
    String user = normalize(filename.substring(0, dot));
    return ACCOUNT.matcher(user).matches() ? user : "";
  }

  static boolean supported(String filename) {
    return filename != null && IMAGE.matcher(filename.toLowerCase(Locale.ROOT)).matches();
  }

  static final class Candidate {
    final String name, uri, user;
    final int width, height;
    final long size, modified;
    final boolean readable;

    Candidate(
        String name,
        String uri,
        int width,
        int height,
        long size,
        long modified,
        boolean readable) {
      this.name = name;
      this.uri = uri;
      this.user = username(name);
      this.width = width;
      this.height = height;
      this.size = size;
      this.modified = modified;
      this.readable = readable;
    }
  }

  static int format(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    return n.endsWith(".webp") ? 0 : n.endsWith(".jpg") || n.endsWith(".jpeg") ? 1 : 2;
  }

  static final Comparator<Candidate> ORDER =
      Comparator.comparing((Candidate c) -> !c.readable)
          .thenComparing(
              Comparator.comparingLong((Candidate c) -> (long) c.width * c.height).reversed())
          .thenComparingInt(c -> format(c.name))
          .thenComparing(c -> c.name)
          .thenComparing(c -> c.uri);

  static final class Catalog {
    final Map<String, Candidate> best = new HashMap<>();
    final Map<String, Integer> counts = new HashMap<>();
    int unreadable, duplicates, files, unknownSizes, nonTargetResolution, unknownResolution;
    long bytes;

    void add(Candidate candidate) {
      files++;
      if (candidate.size >= 0) bytes += candidate.size;
      else unknownSizes++;
      if (!candidate.readable) unreadable++;
      if (candidate.width > 0 && candidate.height > 0) {
        if (candidate.width != 256 || candidate.height != 256) nonTargetResolution++;
      } else unknownResolution++;
      if (candidate.user.isEmpty()) return;
      int count = counts.getOrDefault(candidate.user, 0) + 1;
      counts.put(candidate.user, count);
      if (count > 1) duplicates++;
      Candidate old = best.get(candidate.user);
      if (old == null || ORDER.compare(candidate, old) < 0) best.put(candidate.user, candidate);
    }
  }
}
