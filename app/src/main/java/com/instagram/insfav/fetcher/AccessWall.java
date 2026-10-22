package com.instagram.insfav.fetcher;

import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Detect explicit walls, never bundled feature names or ordinary navigation links. */
final class AccessWall {
    private static final Pattern SCRIPTS = Pattern.compile("<script\\b[^>]*>(.*?)</script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FORMS = Pattern.compile("<form\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTES = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    static String detect(String html) {
        String result = "";
        String visible = SCRIPTS.matcher(html).replaceAll("").replaceAll("(?s)<!--.*?-->", "");
        Matcher forms = FORMS.matcher(visible);
        while (forms.find()) {
            Map<String, String> attributes = new HashMap<>();
            Matcher attribute = ATTRIBUTES.matcher(forms.group(1));
            while (attribute.find()) {
                String value = attribute.group(2) != null ? attribute.group(2) : attribute.group(3) != null ? attribute.group(3) : attribute.group(4);
                attributes.put(attribute.group(1).toLowerCase(Locale.ROOT), value.toLowerCase(Locale.ROOT));
            }
            String id = attributes.getOrDefault("id", ""), action = attributes.getOrDefault("action", "");
            if (id.equals("challengeform") || id.equals("challenge_form") || id.equals("captchaform") || id.equals("checkpointform") || challengePath(action)) return "CHALLENGE_REQUIRED";
            if (id.equals("loginform") || id.equals("login_form") || action.startsWith("/accounts/login/") || action.startsWith("https://www.instagram.com/accounts/login/")) result = "LOGIN_REQUIRED";
        }
        Matcher scripts = SCRIPTS.matcher(html); int count = 0;
        while (scripts.find() && ++count <= 512) {
            String body = scripts.group(1).trim();
            if (body.startsWith("window._sharedData = ")) { body = body.substring("window._sharedData = ".length()).trim(); if (body.endsWith(";")) body = body.substring(0, body.length() - 1); }
            if (!(body.startsWith("{") || body.startsWith("["))) continue;
            try {
                String current = structured(new JSONTokener(body).nextValue(), 0, new int[]{0});
                if (current.equals("CHALLENGE_REQUIRED")) return current;
                if (!current.isEmpty()) result = current;
            } catch (JSONException ignored) { /* Do not evaluate scripts or log server content. */ }
        }
        return result;
    }
    private static boolean challengePath(String value) {
        for (String prefix : new String[]{"/challenge/", "/checkpoint/", "/captcha/", "https://www.instagram.com/challenge/", "https://www.instagram.com/checkpoint/", "https://www.instagram.com/captcha/"}) if (value.startsWith(prefix)) return true;
        return false;
    }
    private static String structured(Object value, int depth, int[] visits) {
        if (depth > 48 || ++visits[0] > 50000) return "";
        String result = "";
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject)value;
            if (Boolean.TRUE.equals(o.opt("challenge_required")) || Boolean.TRUE.equals(o.opt("checkpoint_required"))) return "CHALLENGE_REQUIRED";
            if ("fail".equals(o.optString("status")) && ("challenge_required".equals(o.optString("message")) || "checkpoint_required".equals(o.optString("message")))) return "CHALLENGE_REQUIRED";
            if (Boolean.TRUE.equals(o.opt("login_required")) || ("fail".equals(o.optString("status")) && "login_required".equals(o.optString("message")))) result = "LOGIN_REQUIRED";
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) { String found = structured(o.opt(keys.next()), depth + 1, visits); if (found.equals("CHALLENGE_REQUIRED")) return found; if (!found.isEmpty()) result = found; }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray)value;
            for (int i = 0; i < a.length(); i++) { String found = structured(a.opt(i), depth + 1, visits); if (found.equals("CHALLENGE_REQUIRED")) return found; if (!found.isEmpty()) result = found; }
        }
        return result;
    }
}
