package com.instagram.insfav.fetcher;

import java.net.*;
import java.util.*;
import org.json.*;

/** No arbitrary navigation, post-image fallback, or credentials in snapshot diagnostics. */
final class WebProfilePolicy {
  static boolean resource(String raw) {
    try {
      URL url = new URL(raw);
      String host = url.getHost().toLowerCase(Locale.ROOT);
      return url.getProtocol().equals("https")
          && url.getUserInfo() == null
          && (url.getPort() == -1 || url.getPort() == 443)
          && (host.equals("www.instagram.com")
              || host.equals("instagram.com")
              || host.endsWith(".cdninstagram.com")
              || host.endsWith(".fbcdn.net"));
    } catch (MalformedURLException error) {
      return false;
    }
  }

  static String navigation(String raw, String user) {
    try {
      URL url = new URL(raw);
      String path = url.getPath().toLowerCase(Locale.ROOT);
      if (!PublicProfileProvider.trusted(url, false)) return "WEBVIEW_NAVIGATION_BLOCKED";
      if (path.contains("/challenge") || path.contains("/checkpoint") || path.contains("/captcha"))
        return "CHALLENGE_REQUIRED";
      if (path.startsWith("/accounts/login")) return "COOKIE_INVALID_EXPIRED";
      if (!(path.equals(user.isEmpty() ? "/" : "/" + user + "/")
          || !user.isEmpty() && path.equals("/" + user))) return "WEBVIEW_NAVIGATION_BLOCKED";
      return "";
    } catch (MalformedURLException error) {
      return "WEBVIEW_NAVIGATION_BLOCKED";
    }
  }

  static ProfileProvider.Failure http(int status, boolean main, String retry) {
    if (status == 429)
      return new ProfileProvider.Failure(
          "RATE_LIMITED",
          status,
          PublicProfileProvider.retryAt(retry, System.currentTimeMillis()),
          true);
    if (status == 401)
      return new ProfileProvider.Failure(
          main ? "COOKIE_INVALID_EXPIRED" : "ACCESS_BLOCKED", status, 0, true);
    if (!main) return null;
    if (status == 404)
      return new ProfileProvider.Failure("PROFILE_NOT_FOUND_OR_UNAVAILABLE", status, 0, false);
    if (status == 403) return new ProfileProvider.Failure("PRIVATE_RESTRICTED", status, 0, true);
    return status >= 400 ? new ProfileProvider.Failure("HTTP_ERROR", status, 0, true) : null;
  }

  static JSONObject snapshot(String encoded) throws ProfileProvider.Failure {
    if (encoded == null || encoded.length() > 32768)
      throw new ProfileProvider.Failure("WEBVIEW_SNAPSHOT_INVALID", 0, 0, true);
    try {
      Object value = new JSONTokener(encoded).nextValue();
      if (!(value instanceof String)) throw new JSONException("Invalid snapshot");
      return new JSONObject((String) value);
    } catch (JSONException error) {
      throw new ProfileProvider.Failure("WEBVIEW_SNAPSHOT_INVALID", 0, 0, true);
    }
  }

  static URL image(JSONObject data, String user) throws ProfileProvider.Failure {
    try {
      String kind = data.optString("kind");
      if (!user.equals(data.optString("username"))
          || !navigation(data.optString("page"), user).isEmpty()
          || !(kind.equals("profile_header") || kind.equals("profile_json"))
          || !data.optBoolean("loaded")
          || data.optInt("width") < 32
          || data.optInt("height") < 32
          || data.optInt("width") > 20000
          || data.optInt("height") > 20000)
        throw new ProfileProvider.Failure("PROFILE_IDENTITY_UNVERIFIED", 200, 0, false);
      URL url = new URL(data.getString("url"));
      String lower = url.toString().toLowerCase(Locale.ROOT);
      if (!PublicProfileProvider.trusted(url, true)
          || lower.contains("anonymous")
          || lower.contains("default")
          || lower.contains("logo")
          || lower.contains("44884218_345707102882519_2446069589734326272"))
        throw new ProfileProvider.Failure("UNTRUSTED_SOURCE");
      return url;
    } catch (JSONException | MalformedURLException error) {
      throw new ProfileProvider.Failure("INVALID_IMAGE_URL");
    }
  }

  private WebProfilePolicy() {}
}
