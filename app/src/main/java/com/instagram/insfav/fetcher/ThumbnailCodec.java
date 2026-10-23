package com.instagram.insfav.fetcher;

import android.graphics.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class ThumbnailCodec {
  static final int TARGET_SIZE = 256, LEGACY_SIZE = 128;

  static boolean allowed(int width, int height) {
    return height == width && (width == LEGACY_SIZE || width == TARGET_SIZE);
  }

  static final int MAX_OUTPUT = 256 * 1024;

  static boolean valid(byte[] bytes) {
    if (bytes == null || bytes.length < 20 || bytes.length > MAX_OUTPUT) return false;
    if (!new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
        || !new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return false;
    long riff = 0;
    for (int i = 0; i < 4; i++) riff |= (long) (bytes[4 + i] & 255) << (8 * i);
    if (riff + 8 != bytes.length) return false;
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
    if (!allowed(bounds.outWidth, bounds.outHeight) || !"image/webp".equals(bounds.outMimeType))
      return false;
    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    if (bitmap == null) return false;
    bitmap.recycle();
    return true;
  }

  static boolean completeContainer(byte[] bytes) {
    int n = bytes.length;
    if (n >= 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216)
      return (bytes[n - 2] & 255) == 255 && (bytes[n - 1] & 255) == 217;
    if (n >= 20
        && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
        && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
      long size = 0;
      for (int i = 0; i < 4; i++) size |= (long) (bytes[4 + i] & 255) << (i * 8);
      return size + 8 == n;
    }
    if (n < 33
        || bytes[0] != (byte) 137
        || bytes[1] != 80
        || bytes[2] != 78
        || bytes[3] != 71
        || bytes[4] != 13
        || bytes[5] != 10
        || bytes[6] != 26
        || bytes[7] != 10) return false;
    int offset = 8;
    while (offset + 12 <= n) {
      long length = 0;
      for (int i = 0; i < 4; i++) length = (length << 8) | (bytes[offset + i] & 255);
      if (length > n - offset - 12) return false;
      int count = (int) length;
      java.util.zip.CRC32 crc = new java.util.zip.CRC32();
      crc.update(bytes, offset + 4, count + 4);
      long stored = 0;
      for (int i = 0; i < 4; i++) stored = (stored << 8) | (bytes[offset + 8 + count + i] & 255);
      if (crc.getValue() != stored) return false;
      boolean end = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII).equals("IEND");
      offset += count + 12;
      if (end) return count == 0 && offset == n;
    }
    return false;
  }

  static byte[] convert(byte[] input) throws IOException {
    if (input.length == 0 || input.length > 8 * 1024 * 1024)
      throw new IOException("IMAGE_SIZE_REJECTED");
    if (!completeContainer(input)) throw new IOException("IMAGE_CONTAINER_INVALID");
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(input, 0, input.length, bounds);
    int width = bounds.outWidth, height = bounds.outHeight;
    if (width < 1
        || height < 1
        || width > 16384
        || height > 16384
        || (long) width * height > 40000000
        || !("image/jpeg".equals(bounds.outMimeType)
            || "image/png".equals(bounds.outMimeType)
            || "image/webp".equals(bounds.outMimeType))) throw new IOException("IMAGE_REJECTED");
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = 1;
    while (Math.max(width, height) / options.inSampleSize > 512) options.inSampleSize *= 2;
    Bitmap source = BitmapFactory.decodeByteArray(input, 0, input.length, options);
    if (source == null) throw new IOException("IMAGE_DECODE_FAILED");
    Bitmap result = null;
    try {
      result = Bitmap.createBitmap(TARGET_SIZE, TARGET_SIZE, Bitmap.Config.ARGB_8888);
      int side = Math.min(source.getWidth(), source.getHeight()),
          left = (source.getWidth() - side) / 2,
          top = (source.getHeight() - side) / 2;
      new Canvas(result)
          .drawBitmap(
              source,
              new Rect(left, top, left + side, top + side),
              new Rect(0, 0, TARGET_SIZE, TARGET_SIZE),
              new Paint(Paint.FILTER_BITMAP_FLAG));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      if (!result.compress(Bitmap.CompressFormat.WEBP, 78, output))
        throw new IOException("WEBP_ENCODE_FAILED");
      byte[] bytes = output.toByteArray();
      if (!valid(bytes)) throw new IOException("WEBP_VERIFY_FAILED");
      return bytes;
    } finally {
      if (result != null) result.recycle();
      source.recycle();
    }
  }
}
