package com.instagram.insfav.fetcher;

/** Absence requires evidence; uncertainty stays durable until explicit Retry. */
final class FailurePolicy {
  static boolean unavailable(ProfileProvider.Failure e) {
    return e.confirmedUnavailable
        && (e.code.equals("PROFILE_NOT_FOUND_OR_UNAVAILABLE")
            || e.code.equals("PROFILE_PAGE_UNAVAILABLE"));
  }

  static boolean noImage(ProfileProvider.Failure e) {
    return e.code.equals("PROFILE_DEFAULT_AVATAR");
  }

  static boolean sessionTransient(ProfileProvider.Failure e) {
    return e.code.equals("COOKIE_AUTH_UNVERIFIED")
        || e.code.equals("COOKIE_WEBVIEW_SETUP_TIMEOUT")
        || e.code.equals("WEBVIEW_SESSION_BUSY");
  }

  static boolean retry(ProfileProvider.Failure e) {
    if (unavailable(e) || noImage(e) || pause(e)) return false;
    return sessionTransient(e)
        || e.code.equals("PROFILE_PAGE_UNAVAILABLE")
        || e.code.equals("PROFILE_NOT_FOUND_OR_UNAVAILABLE")
        || e.code.equals("PROFILE_IMAGE_URL_MISSING")
        || e.code.equals("PROFILE_IDENTITY_UNVERIFIED")
        || e.code.equals("PROFILE_DATA_AMBIGUOUS")
        || e.code.equals("WEBVIEW_TIMEOUT")
        || e.code.equals("PROFILE_OPERATION_TIMEOUT")
        || e.code.equals("WEBVIEW_NETWORK_ERROR")
        || e.code.equals("NETWORK_ERROR")
        || e.code.equals("REQUEST_TIMEOUT")
        || e.code.equals("WEBVIEW_NAVIGATION_BLOCKED")
        || e.code.equals("WEBVIEW_RENDERER_GONE")
        || e.code.equals("REQUEST_CANCELLED")
        || e.code.equals("HTTP_ERROR") && e.http >= 500;
  }

  static boolean pause(ProfileProvider.Failure e) {
    if (unavailable(e)
        || noImage(e)
        || sessionTransient(e)
        || e.code.equals("PROFILE_PAGE_UNAVAILABLE")
        || e.code.equals("PROFILE_NOT_FOUND_OR_UNAVAILABLE")
        || e.code.equals("PROFILE_IMAGE_URL_MISSING")
        || e.code.equals("PROFILE_IDENTITY_UNVERIFIED")
        || e.code.equals("PROFILE_DATA_AMBIGUOUS")) return false;
    if (e.code.equals("IMAGE_MEMORY_LIMIT")) return true;
    if (e.code.equals("WEBVIEW_NAVIGATION_BLOCKED")
        || e.code.equals("WEBVIEW_NETWORK_ERROR")
        || e.code.equals("WEBVIEW_TIMEOUT")
        || e.code.equals("PROFILE_OPERATION_TIMEOUT")
        || e.code.equals("NETWORK_ERROR")
        || e.code.equals("REQUEST_TIMEOUT")
        || e.code.equals("WEBVIEW_RENDERER_GONE")
        || e.code.equals("REQUEST_CANCELLED")
        || e.code.equals("HTTP_ERROR") && e.http >= 500) return false;
    return e.http == 429
        || e.http == 401
        || e.http == 403
        || e.code.startsWith("COOKIE_")
        || e.code.startsWith("LOGIN_")
        || e.code.contains("CHALLENGE")
        || e.code.contains("RESTRICTED")
        || e.pause;
  }

  private FailurePolicy() {}
}
