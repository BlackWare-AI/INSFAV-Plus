package com.instagram.insfav.fetcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class Identity {
    static String normalize(String value) {
        return value == null ? "" : value.trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
    }
    static boolean valid(String user) { return user.matches("[a-z0-9._]{1,30}") && !user.startsWith(".") && !user.endsWith(".") && !user.contains(".."); }
    static String outputFilename(String pageId) {
        String user = normalize(pageId); if (!valid(user)) throw new IllegalArgumentException("INVALID_USERNAME"); return user + ".webp";
    }
    static String filename(String pageId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(normalize(pageId).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(69);
            for (byte b : hash) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return result + ".webp";
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
