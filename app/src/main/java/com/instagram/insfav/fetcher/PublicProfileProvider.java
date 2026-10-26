package com.instagram.insfav.fetcher;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Only the public profile page and identity-bound, explicitly non-default photos are accepted. */
final class PublicProfileProvider implements ProfileProvider {
  interface Connections {
    HttpsURLConnection open(URL url) throws IOException;
  }

  private final Connections connections;
  private final CookieJar cookies;
  private final File stagingDirectory;

  PublicProfileProvider() {
    this(url -> (HttpsURLConnection) url.openConnection(), null);
  }

  PublicProfileProvider(CookieJar cookies) {
    this(url -> (HttpsURLConnection) url.openConnection(), cookies);
  }

  PublicProfileProvider(Connections connections) {
    this(connections, null);
  }

  PublicProfileProvider(Connections connections, CookieJar cookies) {
    this(connections, cookies, new File(System.getProperty("java.io.tmpdir"), "insfav-profile-downloads"));
  }

  PublicProfileProvider(File stagingDirectory, CookieJar cookies) {
    this(url -> (HttpsURLConnection) url.openConnection(), cookies, stagingDirectory);
  }

  PublicProfileProvider(Connections connections, CookieJar cookies, File stagingDirectory) {
    this.connections = connections;
    this.cookies = cookies;
    this.stagingDirectory = stagingDirectory;
  }

  @Override
  public void authenticate() throws Failure {
    if (cookies == null || cookies.value("sessionid", System.currentTimeMillis()).isEmpty())
      throw new Failure("COOKIE_REQUIRED");
    try {
      String page =
          new String(
              get(new URL("https://www.instagram.com/"), false, null), StandardCharsets.UTF_8);
      rejectWall(page, true);
      String expectedId = cookies.value("ds_user_id", System.currentTimeMillis());
      if (!authenticated(page, expectedId))
        throw new Failure("COOKIE_AUTH_UNVERIFIED", 200, 0, true);
    } catch (Failure error) {
      throw error;
    } catch (IOException error) {
      throw new Failure("NETWORK_ERROR");
    }
  }

  static boolean authenticated(String html, String expectedId) {
    Matcher matcher = SCRIPT.matcher(html);
    int count = 0;
    while (matcher.find() && ++count <= 512) {
      String body = jsonBody(matcher.group(1));
      if (!(body.startsWith("{") || body.startsWith("["))) continue;
      try {
        if (viewer(new JSONTokener(body).nextValue(), expectedId, 0, new int[] {0})) return true;
      } catch (JSONException ignored) {
      }
    }
    return false;
  }

  private static boolean viewer(Object value, String expectedId, int depth, int[] visits) {
    if (depth > 48 || ++visits[0] > 50000) return false;
    if (value instanceof JSONObject) {
      JSONObject o = (JSONObject) value;
      for (String key : new String[] {"viewer", "current_user"}) {
        JSONObject user = o.optJSONObject(key);
        if (user != null
            && user.opt("username") instanceof String
            && Identity.valid(Identity.normalize(user.optString("username")))) {
          String id = user.optString("id", user.optString("pk"));
          if (id.matches("[0-9]+") && (expectedId.isEmpty() || expectedId.equals(id))) return true;
        }
      }
      String id = o.optString("viewerId", o.optString("viewer_id"));
      if (!expectedId.isEmpty()
          && expectedId.equals(id)
          && id.matches("[0-9]+")
          && !Boolean.FALSE.equals(o.opt("is_logged_in"))
          && !Boolean.FALSE.equals(o.opt("is_authenticated"))) return true;
      Iterator<String> keys = o.keys();
      while (keys.hasNext())
        if (viewer(o.opt(keys.next()), expectedId, depth + 1, visits)) return true;
    } else if (value instanceof JSONArray) {
      JSONArray a = (JSONArray) value;
      for (int i = 0; i < a.length(); i++)
        if (viewer(a.opt(i), expectedId, depth + 1, visits)) return true;
    }
    return false;
  }

  private static String jsonBody(String body) {
    String value = body.trim();
    String prefix = "window._sharedData = ";
    if (value.startsWith(prefix)) {
      value = value.substring(prefix.length()).trim();
      if (value.endsWith(";")) value = value.substring(0, value.length() - 1);
    }
    return value;
  }

  private static void rejectWall(String html, boolean configured) throws Failure {
    String wall = AccessWall.detect(html);
    if (wall.equals("CHALLENGE_REQUIRED")) throw new Failure(wall, 200, 0, true);
    if (wall.equals("LOGIN_REQUIRED"))
      throw new Failure(configured ? "COOKIE_INVALID_EXPIRED" : wall, 200, 0, true);
  }

  private volatile HttpsURLConnection active;
  private volatile boolean cancelled;
    String userAgent = System.getProperty("http.agent", "");
  private static final Pattern SCRIPT =
      Pattern.compile(
          "<script\\b[^>]*>(.*?)</script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  static boolean trusted(URL url, boolean image) {
    if (!url.getProtocol().equals("https")
        || url.getUserInfo() != null
        || (url.getPort() != -1 && url.getPort() != 443)) return false;
    String host = url.getHost().toLowerCase(Locale.ROOT);
    if (!image) return host.equals("www.instagram.com") || host.equals("instagram.com");
    return host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net");
  }

  static boolean placeholder(String raw) {
    String lower = raw.toLowerCase(Locale.ROOT);
    return lower.contains("anonymous")
        || lower.contains("default")
        || lower.contains("logo")
        || lower.contains("44884218_345707102882519_2446069589734326272");
  }

  static long retryAt(String header, long now) {
    if (header == null) return now + 60_000;
    try {
      long seconds = Long.parseLong(header.trim());
      return now + Math.min(Math.max(0, seconds), 30L * 24 * 3600) * 1000;
    } catch (NumberFormatException ignored) {
      try {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        format.setLenient(false);
        return Math.max(now, format.parse(header).getTime());
      } catch (Exception invalid) {
        return now + 60_000;
      }
    }
  }

  @Override
  public void cancel() {
    cancelled = true;
    HttpsURLConnection connection = active;
    if (connection != null) connection.disconnect();
  }

  @Override
  public byte[] fetch(String username) throws Failure {
    try {
      byte[] html = get(new URL("https://www.instagram.com/" + username + "/"), false, username);
      String page = new String(html, StandardCharsets.UTF_8);
      rejectWall(page, cookies != null);
      URL image = photo(page, username);
      return get(image, true, username);
    } catch (Failure failure) {
      throw failure;
    } catch (IOException error) {
      throw new Failure(
          cancelled
              ? "REQUEST_CANCELLED"
              : error instanceof UnknownHostException
                  ? "DNS_ERROR"
                  : error instanceof javax.net.ssl.SSLException
                      ? "TLS_ERROR"
                      : error instanceof java.net.SocketTimeoutException
                          ? "NETWORK_TIMEOUT"
                          : "NETWORK_ERROR");
    }
  }

  byte[] download(URL url) throws Failure {
    try {
      return get(url, true, null);
    } catch (Failure error) {
      throw error;
    } catch (IOException error) {
      throw new Failure("DOWNLOAD_FAILED");
    }
  }

  private byte[] get(URL url, boolean image, String user) throws IOException, Failure {
    long deadline = System.nanoTime() + 45_000_000_000L;
    for (int redirect = 0; redirect <= 3; redirect++) {
      if (cancelled) throw new Failure("REQUEST_CANCELLED");
      if (System.nanoTime() > deadline) throw new Failure("REQUEST_TIMEOUT");
      if (!trusted(url, image) || image && placeholder(url.toString()))
        throw new Failure("UNTRUSTED_SOURCE");
      if (!image && !url.getPath().equals(user == null ? "/" : "/" + user + "/")) {
        String path = url.getPath().toLowerCase(Locale.ROOT);
        throw new Failure(
            path.contains("challenge") || path.contains("captcha")
                ? "CHALLENGE_REQUIRED"
                : path.contains("accounts/login")
                    ? cookies == null ? "LOGIN_REQUIRED" : "COOKIE_INVALID_EXPIRED"
                    : "LOGIN_OR_REDIRECT_BLOCKED",
            0,
            0,
            true);
      }
      HttpsURLConnection connection = connections.open(url);
      active = connection;
      try {
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", userAgent);
        if (!image && cookies != null) {
          if (cookies.value("sessionid", System.currentTimeMillis()).isEmpty())
            throw new Failure("COOKIE_EXPIRED", 0, 0, true);
          connection.setRequestProperty("Cookie", cookies.header(url, System.currentTimeMillis()));
        }
        connection.setRequestProperty(
            "Accept", image ? "image/webp,image/jpeg,image/png" : "text/html");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (image) connection.setRequestProperty("Referer", "https://www.instagram.com/");
        if (cancelled) throw new Failure("REQUEST_CANCELLED");
                int status = connection.getResponseCode();
        if (cancelled) throw new Failure("REQUEST_CANCELLED");
        if (System.nanoTime() > deadline) throw new Failure("REQUEST_TIMEOUT");
        if (status == 429)
          throw new Failure(
              "RATE_LIMITED",
              status,
              retryAt(connection.getHeaderField("Retry-After"), System.currentTimeMillis()),
              true);
        if (status == 401)
          throw new Failure(
              image
                  ? "DOWNLOAD_FAILED"
                  : cookies == null ? "LOGIN_REQUIRED" : "COOKIE_INVALID_EXPIRED",
              status,
              0,
              true);
        if (status == 403)
          throw new Failure(image ? "DOWNLOAD_FAILED" : "PRIVATE_RESTRICTED", status, 0, true);
        if (status == 404)
          throw new Failure(
              image ? "DOWNLOAD_FAILED" : "PROFILE_NOT_FOUND_OR_UNAVAILABLE", status, 0, false);
        if (status >= 300 && status < 400) {
          String location = connection.getHeaderField("Location");
          if (location == null) throw new Failure("REDIRECT_INVALID", status, 0, true);
          url = new URL(url, location);
          continue;
        }
        if (status != 200)
          throw new Failure(image ? "DOWNLOAD_FAILED" : "HTTP_ERROR", status, 0, false);
        String type = connection.getContentType();
        if (type == null
            || !(image
                ? type.matches("(?i)image/(jpeg|png|webp)(;.*)?")
                : type.toLowerCase(Locale.ROOT).startsWith("text/html")))
          throw new Failure("CONTENT_TYPE_REJECTED", status, 0, false);
        int limit = image ? 8 * 1024 * 1024 : 4 * 1024 * 1024;
        if (connection.getContentLengthLong() > limit) throw new Failure("RESPONSE_TOO_LARGE");
        if (image) {
          try (InputStream input = connection.getInputStream()) {
            return DownloadStaging.read(input, stagingDirectory, limit,
                connection.getContentLengthLong(), deadline, () -> cancelled);
          }
        }
        try (InputStream input = connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
          byte[] buffer = new byte[8192];
          int n;
          while ((n = input.read(buffer)) != -1) {
            if (cancelled || System.nanoTime() > deadline) throw new Failure("REQUEST_TIMEOUT");
            if (bytes.size() + n > limit) throw new Failure("RESPONSE_TOO_LARGE");
            bytes.write(buffer, 0, n);
          }
          return bytes.toByteArray();
        }
      } finally {
        connection.disconnect();
        active = null;
      }
    }
    throw new Failure("REDIRECT_LIMIT", 0, 0, true);
  }

  static URL photo(String html, String user) throws Failure {
    Matcher matcher = SCRIPT.matcher(html);
    LinkedHashSet<String> found = new LinkedHashSet<>();
    boolean[] restricted = {false};
    boolean errorPage = false;
    int scripts = 0;
    while (matcher.find() && ++scripts <= 512) {
      String body = jsonBody(matcher.group(1));
      if (!(body.startsWith("{") || body.startsWith("["))) continue;
      try {
        Object value = new JSONTokener(body).nextValue();
        find(value, user, found, 0, new int[] {0}, restricted);
        errorPage |= profileErrorPage(value, 0, new int[] {0});
      } catch (JSONException ignored) {
        /* JavaScript is not JSON; never evaluate it. */
      }
    }
    if (found.isEmpty() && restricted[0]) throw new Failure("PRIVATE_RESTRICTED", 200, 0, false);
    if (found.size() != 1) {
      rejectWall(html, false);
      if (found.isEmpty() && errorPage) throw new Failure("PROFILE_PAGE_UNAVAILABLE", 200, 0, true);
      throw new Failure(
          found.isEmpty() ? "PROFILE_IMAGE_URL_MISSING" : "PROFILE_DATA_AMBIGUOUS", 200, 0, false);
    }
    try {
      URL url = new URL(found.iterator().next());
      if (!trusted(url, true)) throw new Failure("UNTRUSTED_SOURCE");
      return url;
    } catch (MalformedURLException error) {
      throw new Failure("INVALID_IMAGE_URL");
    }
  }

  // Inspect routed server data, not module names mentioned in JavaScript bundles.
  private static boolean profileErrorPage(Object value, int depth, int[] visits) {
    if (depth > 48 || ++visits[0] > 50000) return false;
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value, view = object.optJSONObject("rootView");
      JSONObject resource = view == null ? null : view.optJSONObject("resource");
      if (resource != null && "PolarisErrorRoot.react".equals(resource.optString("__dr")))
        return true;
      Iterator<String> keys = object.keys();
      while (keys.hasNext())
        if (profileErrorPage(object.opt(keys.next()), depth + 1, visits)) return true;
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++)
        if (profileErrorPage(array.opt(i), depth + 1, visits)) return true;
    }
    return false;
  }

  private static void find(
      Object value, String user, Set<String> found, int depth, int[] visits, boolean[] restricted) {
    if (depth > 48 || ++visits[0] > 50000) return;
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      if (user.equals(Identity.normalize(object.optString("username")))
          && object.optString("id", object.optString("pk")).matches("[0-9]+")
          && Boolean.TRUE.equals(object.opt("is_private"))) restricted[0] = true;
      // Accept only a bound account object; never promote a generic og:image fallback.
      if (object.opt("username") instanceof String
          && user.equals(Identity.normalize(object.optString("username")))
          && object.optString("id", object.optString("pk")).matches("[0-9]+")
          && (!object.has("has_anonymous_profile_picture")
              || object.opt("has_anonymous_profile_picture") instanceof Boolean)
          && !Boolean.TRUE.equals(object.opt("has_anonymous_profile_picture"))
          && !"true".equals(object.optString("has_anonymous_profile_picture"))
          && !Boolean.FALSE.equals(object.opt("has_profile_pic"))) {
        String picture = object.optString("profile_pic_url_hd");
        if (picture.isEmpty()) picture = object.optString("profile_pic_url");
        if (picture.isEmpty()) {
          JSONObject info = object.optJSONObject("hd_profile_pic_url_info");
          if (info == null) info = object.optJSONObject("profile_pic_url_info");
          if (info != null) picture = info.optString("url");
        }
        if (!picture.isEmpty() && !placeholder(picture)) found.add(picture);
      }
      Iterator<String> keys = object.keys();
      while (keys.hasNext())
        find(object.opt(keys.next()), user, found, depth + 1, visits, restricted);
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++)
        find(array.opt(i), user, found, depth + 1, visits, restricted);
    }
  }
}
