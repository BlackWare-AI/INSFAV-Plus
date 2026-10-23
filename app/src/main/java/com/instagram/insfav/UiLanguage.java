package com.instagram.insfav;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** Presentation locale only. Instagram account identity always uses Locale.ROOT. */
public final class UiLanguage {
  public static String choice(Context c) {
    return c.getSharedPreferences("plus-ui", 0).getString("language", "system");
  }

  public static void save(Context c, String choice) throws java.io.IOException {
    if (!java.util.Arrays.asList("system", "en", "tr").contains(choice))
      throw new java.io.IOException("Unsupported language");
    if (!c.getSharedPreferences("plus-ui", 0).edit().putString("language", choice).commit())
      throw new java.io.IOException("Language could not be saved");
  }

  static String resolved(Context c) {
    String selected = choice(c);
    return "system".equals(selected)
        ? ("tr"
                .equals(
                    android.content.res.Resources.getSystem()
                        .getConfiguration()
                        .getLocales()
                        .get(0)
                        .getLanguage())
            ? "tr"
            : "en")
        : selected;
  }

  public static Context context(Context c) {
    Locale language = Locale.forLanguageTag(resolved(c));
    Configuration config = new Configuration(c.getResources().getConfiguration());
    config.setLocale(language);
    return c.createConfigurationContext(config);
  }

  private UiLanguage() {}
}
