package com.instagram.insfav;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UiLayoutTest {
  @Test
  public void listAndEveryMatrixSizePersistAndRejectInvalidColumns() throws Exception {
    android.content.Context c = RuntimeEnvironment.getApplication();
    for (int columns : new int[] {0, 2, 3, 4, 5}) {
      UiLayout.save(c, columns);
      assertEquals(columns, UiLayout.choice(c));
    }
    assertThrows(java.io.IOException.class, () -> UiLayout.save(c, 6));
    assertEquals(5, UiLayout.choice(c));
  }
  @Test
  public void folderColumnsPersistIndependentlyFromAccountColumns() throws Exception {
    android.content.Context c = RuntimeEnvironment.getApplication();
    c.getSharedPreferences("plus-ui", 0).edit().clear().commit();
    assertEquals(0, UiLayout.folderChoice(c));
    UiLayout.save(c, 4);
    for (int n : new int[] {0, 2, 3, 4, 5}) {
      UiLayout.saveFolder(c, n);
      assertEquals(n, UiLayout.folderChoice(c));
      assertEquals(4, UiLayout.choice(c));
    }
    assertThrows(java.io.IOException.class, () -> UiLayout.saveFolder(c, 6));
    assertEquals(5, UiLayout.folderChoice(c));
    UiLayout.save(c,2);assertEquals(5,UiLayout.folderChoice(c));
  }
}
