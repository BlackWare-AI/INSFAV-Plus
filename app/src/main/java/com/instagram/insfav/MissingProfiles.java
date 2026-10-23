package com.instagram.insfav;

import android.content.*;
import java.util.*;
import org.json.JSONObject;

/** Builds a private clipboard payload from the same catalog used by Missing Images. */
final class MissingProfiles {
  static String text(Collection<String> users, JSONObject profiles, boolean usable) {
    Set<String> missing = new LinkedHashSet<>();
    for (String value : users) {
      String user = ThumbnailPolicy.normalize(value);
      if (!user.matches("[a-z0-9._]{1,30}")) continue;
      JSONObject image = profiles == null ? null : profiles.optJSONObject(user);
      if (!usable || image == null || !image.optBoolean("readable")) missing.add(user);
    }
    return String.join("\n", missing);
  }

  static int copy(Context context, String text) {
    if (text.isEmpty()) return 0;
    context
        .getSystemService(ClipboardManager.class)
        .setPrimaryClip(ClipData.newPlainText("Missing profile usernames", text));
    return text.split("\n").length;
  }

  private MissingProfiles() {}
}
