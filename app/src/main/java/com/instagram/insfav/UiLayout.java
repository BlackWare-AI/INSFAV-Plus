package com.instagram.insfav;

import android.content.Context;
import java.io.IOException;

/** Zero keeps the original list; other values select matrix columns. */
public final class UiLayout {
  public static int choice(Context c) {
    return c.getSharedPreferences("plus-ui", 0).getInt("columns", 0);
  }

  public static void save(Context c, int columns) throws IOException {
    if (columns != 0 && columns != 2 && columns != 3 && columns != 4 && columns != 5)
      throw new IOException("Unsupported view mode");
    if (!c.getSharedPreferences("plus-ui", 0).edit().putInt("columns", columns).commit())
      throw new IOException("View mode could not be saved");
  }

  private UiLayout() {}
}
