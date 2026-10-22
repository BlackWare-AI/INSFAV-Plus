package com.instagram.insfav;

/** Native presentation uses the same catalog as the WebView without dynamic resource names. */
public final class UiText {
  public static String text(android.content.Context context, String source) {
    android.content.Context localized = UiLanguage.context(context);
    String[] sources = localized.getResources().getStringArray(R.array.ui_sources);
    for (int i = 0; i < sources.length; i++)
      if (sources[i].equals(source))
        return localized.getResources().getStringArray(R.array.ui_values)[i];
    return source;
  }

  private UiText() {}
}
