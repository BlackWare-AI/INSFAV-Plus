package com.instagram.insfav.fetcher;

import java.net.URL;
import java.util.*;
import org.json.*;

/**
 * User-pasted secrets only. No file import, global CookieHandler, or string representation of
 * values.
 */
final class CookieJar {
  static final int MAX_INPUT = 65536;

  static final class Entry {
    final String name, value, domain, path;
    final boolean subdomains;
    final long expires;

    Entry(String name, String value, String domain, String path, boolean subdomains, long expires) {
      this.name = name;
      this.value = value;
      this.domain = domain;
      this.path = path;
      this.subdomains = subdomains;
      this.expires = expires;
    }

    @Override
    public String toString() {
      return "[redacted cookie]";
    }
  }

  final List<Entry> entries;

  CookieJar(List<Entry> entries) {
    this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
  }

    static CookieJar parse(String input, long now) throws ProfileProvider.Failure {
        if (input == null || input.length() == 0 || input.length() > MAX_INPUT) throw new ProfileProvider.Failure("COOKIE_INVALID");
        if (input.startsWith("\uFEFF")) input = input.substring(1);
        List<Entry> values = new ArrayList<>(); boolean netscape = input.contains("\t") || input.trim().startsWith("# Netscape")
            || java.util.regex.Pattern.compile("(?m)^\\s*(?:#HttpOnly_)?\\.?(?:www\\.)?instagram\\.com[ \\t]+(?:TRUE|FALSE)[ \\t]+/", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(input).find();
        boolean expiredSession = false;
        if (netscape) {
            for (String original : input.split("\\r?\\n")) {
                String line = original.replaceFirst("^[ \t]+", ""); if (line.startsWith("#HttpOnly_")) line = line.substring(10);
                else if (line.startsWith("#") || line.trim().isEmpty()) continue;
                String first = line.split("[ \\t]+", 2)[0].toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
                if (!first.equals("instagram.com") && !first.equals("www.instagram.com")) continue;
                String[] fields = line.contains("\t") ? line.split("\t", -1) : line.split(" +", 7);
                if (fields.length != 7) throw new ProfileProvider.Failure("COOKIE_INVALID");
                for (int i = 0; i < fields.length; i++) fields[i] = fields[i].trim();
                fields[1] = fields[1].toUpperCase(Locale.ROOT); fields[3] = fields[3].toUpperCase(Locale.ROOT);
                String domain = fields[0].toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
                if (!domain.equals("instagram.com") && !domain.equals("www.instagram.com")) continue;
                if (!(fields[1].equals("TRUE") || fields[1].equals("FALSE")) || !(fields[3].equals("TRUE") || fields[3].equals("FALSE"))) throw new ProfileProvider.Failure("COOKIE_INVALID");
                long expires;
                try { expires = Long.parseLong(fields[4]); if (expires < 0 || expires > Long.MAX_VALUE / 1000) throw new NumberFormatException(); }
                catch (NumberFormatException e) { throw new ProfileProvider.Failure("COOKIE_INVALID"); }
                if (!fields[2].startsWith("/") || fields[2].contains("\r") || fields[2].contains("\n")) throw new ProfileProvider.Failure("COOKIE_INVALID");
                validate(fields[5], fields[6]);
                if (expires != 0 && expires <= now / 1000) { if (fields[5].equals("sessionid")) expiredSession = true; continue; }
                values.add(new Entry(fields[5], fields[6], domain, fields[2], fields[1].equals("TRUE"), expires));
            }
        } else {
            String line = input.trim(); if (line.regionMatches(true, 0, "Cookie:", 0, 7)) line = line.substring(7).trim();
            if (line.contains("\r") || line.contains("\n")) throw new ProfileProvider.Failure("COOKIE_INVALID");
            for (String item : line.split(";")) {
                int equals = item.indexOf('='); if (equals < 1) throw new ProfileProvider.Failure("COOKIE_INVALID");
                String name = item.substring(0, equals).trim(), value = item.substring(equals + 1).trim();
                validate(name, value); values.add(new Entry(name, value, "www.instagram.com", "/", false, 0));
            }
        }
        if (values.size() > 200) throw new ProfileProvider.Failure("COOKIE_INVALID");
        CookieJar result = new CookieJar(values);
        String session = result.value("sessionid", now);
        if (session.isEmpty()) throw new ProfileProvider.Failure(expiredSession ? "COOKIE_EXPIRED" : "COOKIE_INVALID");
        if (result.headerLength(now) > 16384) throw new ProfileProvider.Failure("COOKIE_INVALID");
        return result;
    }
  private static void validate(String name, String value) throws ProfileProvider.Failure {
    if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        || name.length() > 128
        || value.length() > 8192) throw new ProfileProvider.Failure("COOKIE_INVALID");
    boolean quoted =
        value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"';
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x21 || c > 0x7e || c == ';') throw new ProfileProvider.Failure("COOKIE_INVALID");
      if (quoted && (i == 0 || i == value.length() - 1)) continue;
      if (quoted && c == '\\') {
        if (++i >= value.length() - 1
            || value.charAt(i) < 0x21
            || value.charAt(i) > 0x7e
            || value.charAt(i) == ';') throw new ProfileProvider.Failure("COOKIE_INVALID");
        continue;
      }
      if (c == '"' || c == '\\' || (!quoted && c == ','))
        throw new ProfileProvider.Failure("COOKIE_INVALID");
    }
  }

  String header(URL target, long now) throws ProfileProvider.Failure {
    String host = target.getHost().toLowerCase(Locale.ROOT);
    if (!target.getProtocol().equals("https")
        || target.getUserInfo() != null
        || (target.getPort() != -1 && target.getPort() != 443)
        || (!host.equals("instagram.com") && !host.equals("www.instagram.com"))) return "";
    LinkedHashMap<String, Entry> selected = new LinkedHashMap<>();
    for (Entry e : entries) {
      if (e.expires != 0 && e.expires <= now / 1000) continue;
      if (!(host.equals(e.domain) || e.subdomains && host.endsWith("." + e.domain))) continue;
      String path = target.getPath().isEmpty() ? "/" : target.getPath();
      if (!(path.equals(e.path) || path.startsWith(e.path.endsWith("/") ? e.path : e.path + "/")))
        continue;
      Entry old = selected.get(e.name);
      if (old == null || e.path.length() > old.path.length()) selected.put(e.name, e);
      else if (e.path.length() == old.path.length() && !e.value.equals(old.value))
        throw new ProfileProvider.Failure("COOKIE_AMBIGUOUS");
    }
    StringBuilder header = new StringBuilder();
    for (Entry e : selected.values()) {
      if (header.length() > 0) header.append("; ");
      header.append(e.name).append('=').append(e.value);
    }
    if (header.length() > 16384) throw new ProfileProvider.Failure("COOKIE_INVALID");
    return header.toString();
  }

  String value(String name, long now) throws ProfileProvider.Failure {
    try {
      String header = header(new URL("https://www.instagram.com/"), now);
      for (String item : header.split("; "))
        if (item.startsWith(name + "=")) return item.substring(name.length() + 1);
      return "";
    } catch (java.net.MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  private int headerLength(long now) throws ProfileProvider.Failure {
    try {
      return header(new URL("https://www.instagram.com/"), now).length();
    } catch (java.net.MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  String encode() throws JSONException {
    JSONArray array = new JSONArray();
    for (Entry e : entries)
      array.put(
          new JSONObject()
              .put("name", e.name)
              .put("value", e.value)
              .put("domain", e.domain)
              .put("path", e.path)
              .put("subdomains", e.subdomains)
              .put("expires", e.expires));
    return array.toString();
  }

  static CookieJar decode(String raw) throws JSONException, ProfileProvider.Failure {
    JSONArray array = new JSONArray(raw);
    if (array.length() > 200) throw new ProfileProvider.Failure("COOKIE_INVALID");
    List<Entry> entries = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject o = array.getJSONObject(i);
      validate(o.getString("name"), o.getString("value"));
      String domain = o.getString("domain");
      if (!domain.equals("instagram.com") && !domain.equals("www.instagram.com"))
        throw new ProfileProvider.Failure("COOKIE_INVALID");
      entries.add(
          new Entry(
              o.getString("name"),
              o.getString("value"),
              domain,
              o.getString("path"),
              o.getBoolean("subdomains"),
              o.getLong("expires")));
    }
    return new CookieJar(entries);
  }

  @Override
  public String toString() {
    return "[encrypted cookie jar]";
  }
}
