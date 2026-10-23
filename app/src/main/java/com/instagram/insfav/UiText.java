package com.instagram.insfav;

import android.content.Context;
import java.util.*;

/**
 * Native labels share the web catalog. Fixed two-language arrays avoid per-label resource churn.
 */
public final class UiText {
  private static Map<String, Integer> indices;
  private static final Map<String, String[]> values = new HashMap<>();

  public static synchronized String text(Context context, String source) {
    if (indices == null) {
      indices = new HashMap<>();
      String[] keys = context.getResources().getStringArray(R.array.ui_sources);
      for (int i = 0; i < keys.length; i++) indices.put(keys[i], i);
    }
    Integer index = indices.get(source);
    if (index == null) return source;
    String language = UiLanguage.resolved(context);
    String[] translated = values.get(language);
    if (translated == null) {
      translated = UiLanguage.context(context).getResources().getStringArray(R.array.ui_values);
      values.put(language, translated);
    }
    return translated[index];
  }

  private UiText() {}
}
