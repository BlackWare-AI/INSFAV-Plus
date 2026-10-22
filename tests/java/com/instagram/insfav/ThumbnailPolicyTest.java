package com.instagram.insfav;

import java.util.*;

public final class ThumbnailPolicyTest {
  static int passed;

  static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    passed++;
    System.out.println("PASS " + label);
  }

  static ThumbnailPolicy.Candidate c(String name, int size, boolean readable) {
    return new ThumbnailPolicy.Candidate(
        name, "content://fixture/" + name, size, size, 100, 1, readable);
  }

  public static void main(String[] args) {
    check(
        ThumbnailPolicy.username("@EXAMPLE.User.WEBP").equals("example.user"),
        "normalize leading @, case and extension");
    check(
        ThumbnailPolicy.username("name.with.dots.jpeg").equals("name.with.dots"),
        "preserve periods in usernames");
    check(ThumbnailPolicy.username("user.PNG").equals("user"), "accept uppercase PNG");
    check(ThumbnailPolicy.username("user.JPG").equals("user"), "accept uppercase JPG");
    check(ThumbnailPolicy.username("../user.png").isEmpty(), "reject traversal identity");
    check(ThumbnailPolicy.username("bad name.webp").isEmpty(), "reject spaces in identity");
    check(!ThumbnailPolicy.supported("user.gif"), "reject unsupported extension");
    check(!ThumbnailPolicy.supported("user.jpg.exe"), "reject disguised extension");
    check(ThumbnailPolicy.username(".png").isEmpty(), "reject empty stem");
    ThumbnailPolicy.Catalog catalog = new ThumbnailPolicy.Catalog();
    catalog.add(c("Example.png", 512, false));
    catalog.add(c("example.webp", 128, true));
    check(
        catalog.best.get("example").name.equals("example.webp"),
        "valid candidate outranks unreadable larger image");
    catalog.add(c("EXAMPLE.jpg", 1024, true));
    check(catalog.best.get("example").name.equals("EXAMPLE.jpg"), "larger readable image wins");
    check(catalog.duplicates == 2, "duplicates count additional candidate files");
    check(catalog.unreadable == 1, "unreadable counts files independently");
    check(catalog.bytes == 300, "folder bytes count source candidate files");
    check(catalog.best.size() == 1, "different case and extension share one index identity");
    ThumbnailPolicy.Catalog reversed = new ThumbnailPolicy.Catalog();
    reversed.add(c("EXAMPLE.jpg", 1024, true));
    reversed.add(c("example.webp", 128, true));
    reversed.add(c("Example.png", 512, false));
    check(
        reversed.best.get("example").name.equals(catalog.best.get("example").name),
        "choice is independent of provider cursor order");
    ThumbnailPolicy.Catalog tie = new ThumbnailPolicy.Catalog();
    tie.add(c("user.jpg", 128, true));
    tie.add(c("user.webp", 128, true));
    check(
        tie.best.get("user").name.equals("user.webp"),
        "WebP is preferred at equal valid resolution");
    for (int n : new int[] {1000, 5000, 10000}) {
      long started = System.nanoTime();
      ThumbnailPolicy.Catalog large = new ThumbnailPolicy.Catalog();
      Set<String> users = new HashSet<>();
      for (int i = 0; i < n; i++) {
        String user = "user" + i;
        users.add(user);
        large.add(c(user + ".webp", 128, true));
      }
      check(
          large.best.size() == n
              && large.best.values().stream().allMatch(candidate -> candidate.readable),
          "catalog matches " + n + " unique accounts");
      System.out.println("Catalog " + n + ": " + ((System.nanoTime() - started) / 1000000) + " ms");
    }
    System.out.println(passed + " policy checks passed");
  }
}
