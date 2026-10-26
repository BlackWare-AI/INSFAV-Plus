package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.graphics.*;
import java.io.*;
import java.util.Arrays;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CodecTest {
  static byte[] png(int width, int height) {
    return png(width, height, Color.RED);
  }

  static byte[] png(int width, int height, int color) {
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(color);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    bitmap.recycle();
    return output.toByteArray();
  }

  @Test
  public void convertsLandscapePortraitAndSmallImages() throws Exception {
    for (int[] shape : new int[][] {{900, 300}, {300, 900}, {24, 24}}) {
      byte[] bytes = ThumbnailCodec.convert(png(shape[0], shape[1]));
      assertTrue(ThumbnailCodec.valid(bytes));
      Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
      assertEquals(256, bitmap.getWidth());
      assertEquals(256, bitmap.getHeight());
      bitmap.recycle();
    }
  }

  @Test
  public void acceptsLegacy128WebpWithoutRewritingIt() {
    Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    b.compress(Bitmap.CompressFormat.WEBP, 78, o);
    b.recycle();
    assertTrue(ThumbnailCodec.valid(o.toByteArray()));
  }

  @Test
  public void rejectsBrokenTruncatedAndWrongSizeOutput() throws Exception {
    assertFalse(ThumbnailCodec.valid(png(128, 128)));
    try {
      ThumbnailCodec.convert(new byte[] {1, 2, 3});
      fail();
    } catch (IOException expected) {
    }
    byte[] valid = ThumbnailCodec.convert(png(200, 200));
    assertFalse(ThumbnailCodec.valid(Arrays.copyOf(valid, valid.length - 10)));
    assertFalse(ThumbnailCodec.valid(new byte[ThumbnailCodec.MAX_OUTPUT + 1]));
  }

    @Test public void rejectsMissingSource() throws Exception {
        assertThrows(IOException.class, () -> ThumbnailCodec.convert(null));
    }

    @Test public void rejectsFullyTransparentSource() throws Exception {
        Bitmap blank = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        blank.compress(Bitmap.CompressFormat.PNG, 100, out);
        blank.recycle();
        assertThrows(IOException.class, () -> ThumbnailCodec.convert(out.toByteArray()));
    }


    @Test public void acceptsVisiblePixelsInOtherwiseTransparentSource() throws Exception {
        Bitmap image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        image.setPixel(128, 128, Color.RED);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.PNG, 100, out);
        image.recycle();
        assertTrue(ThumbnailCodec.valid(ThumbnailCodec.convert(out.toByteArray())));
    }
}
