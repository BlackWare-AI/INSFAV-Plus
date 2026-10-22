package com.instagram.insfav.fetcher;

import org.json.JSONObject;

/** One response's DOM, text and route are one observation, regardless of poll count. */
final class ProbeDecision {
    static ProfileProvider.Failure deadline(long now, long deadline) {
        return now >= deadline ? new ProfileProvider.Failure("WEBVIEW_TIMEOUT") : null;
    }
    static ProfileProvider.Failure failure(JSONObject data, String user, int http, boolean verified, boolean everCandidate) {
        String wall = data.optString("wall");
        if (wall.isEmpty()) return null;
        switch (wall) {
            case "PROFILE_PAGE_UNAVAILABLE":
                if (data.optBoolean("candidateFound")) return null;
                // User-selected fast-skip policy: explicit text is sufficient, including HTTP 200/unknown.
                boolean confirmed = !user.isEmpty() && data.optBoolean("unavailableText");
                return new ProfileProvider.Failure(wall, http, 0, false, confirmed);
            case "PROFILE_DEFAULT_AVATAR":
                return data.optBoolean("identityConfirmed") && data.optBoolean("searchCompleted") && data.optBoolean("pageReady")
                    && !everCandidate ? new ProfileProvider.Failure(wall, http, 0, false) : null;
            case "PROFILE_DATA_AMBIGUOUS":
                return new ProfileProvider.Failure(wall, http, 0, false);
            case "COOKIE_INVALID_EXPIRED": case "CHALLENGE_REQUIRED": case "ACCESS_BLOCKED":
                return new ProfileProvider.Failure(wall, http, 0, true);
            case "RATE_LIMITED":
                return new ProfileProvider.Failure(wall, http, PublicProfileProvider.retryAt(null, System.currentTimeMillis()), true);
            case "UNTRUSTED_SOURCE":
                return new ProfileProvider.Failure(wall, http, 0, false);
            default: return new ProfileProvider.Failure("WEBVIEW_SNAPSHOT_INVALID", 0, 0, true);
        }
    }
    private ProbeDecision() { }
}
