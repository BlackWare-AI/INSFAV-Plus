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
    for (int columns : new int[] {0, 2, 3, 4}) {
      UiLayout.save(c, columns);
      assertEquals(columns, UiLayout.choice(c));
    }
    assertThrows(java.io.IOException.class, () -> UiLayout.save(c, 5));
    assertEquals(4, UiLayout.choice(c));
  }
}
