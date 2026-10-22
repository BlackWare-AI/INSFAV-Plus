package com.instagram.insfav;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.graphics.*;
import android.net.Uri;
import android.os.CancellationSignal;
import java.io.*;

/** Bounded local decoding. Every source stream is closed before returning. */
final class ImageCodec {
  static final class Inspection {
    final int width, height;
    final boolean readable;

    Inspection(int width, int height, boolean readable) {
      this.width = width;
      this.height = height;
      this.readable = readable;
    }
  }

  /**
   * Rewind a small bounded header buffer instead of reopening typical 128/256 thumbnails. Keep
   * pixel validation: a plausible header alone does not prove that an image decodes.
   */
  static Inspection inspect(ContentResolver resolver, Uri uri) throws IOException {
    return inspect(resolver, uri, null);
  }

  static Inspection inspect(ContentResolver resolver, Uri uri, CancellationSignal cancellation)
      throws IOException {
    return inspect(resolver, uri, cancellation, null);
  }

  static final class Timings {
    final java.util.concurrent.atomic.AtomicLong
        opens = new java.util.concurrent.atomic.AtomicLong(),
        decodes = new java.util.concurrent.atomic.AtomicLong();
  }

  static Inspection inspect(
      ContentResolver resolver, Uri uri, CancellationSignal cancellation, Timings timings)
      throws IOException {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    try (InputStream raw = timedOpen(resolver, uri, cancellation, timings);
        BufferedInputStream input = new BufferedInputStream(raw, 65536)) {
      input.mark(65536);
      timedDecode(input, bounds, timings);
      if (cancellation != null) cancellation.throwIfCanceled();
      if (!validBounds(bounds)) return new Inspection(0, 0, false);
      BitmapFactory.Options options = sampled(bounds, 128);
      Bitmap image;
      try {
        input.reset();
      } catch (IOException headerExceedsBuffer) {
        // Unusual large headers retain the existing bounded two-open path.
        try (InputStream retry = timedOpen(resolver, uri, cancellation, timings)) {
          image = timedDecode(retry, options, timings);
        }
        return inspected(bounds, image);
      }
      image = timedDecode(input, options, timings);
      return inspected(bounds, image);
    }
  }

  private static InputStream timedOpen(
      ContentResolver resolver, Uri uri, CancellationSignal cancellation, Timings timings)
      throws IOException {
    long began = System.nanoTime();
    try {
      return open(resolver, uri, cancellation);
    } finally {
      if (timings != null) timings.opens.addAndGet(System.nanoTime() - began);
    }
  }

  private static Bitmap timedDecode(
      InputStream input, BitmapFactory.Options options, Timings timings) {
    long began = System.nanoTime();
    try {
      return BitmapFactory.decodeStream(input, null, options);
    } finally {
      if (timings != null) timings.decodes.addAndGet(System.nanoTime() - began);
    }
  }

  private static Inspection inspected(BitmapFactory.Options bounds, Bitmap image) {
    boolean readable = image != null;
    if (image != null) image.recycle();
    return new Inspection(bounds.outWidth, bounds.outHeight, readable);
  }

  private static boolean validBounds(BitmapFactory.Options options) {
    int w = options.outWidth, h = options.outHeight;
    return w > 0
        && h > 0
        && w <= 32768
        && h <= 32768
        && (long) w * h <= 100000000L
        && ("image/jpeg".equals(options.outMimeType)
            || "image/png".equals(options.outMimeType)
            || "image/webp".equals(options.outMimeType));
  }

  private static BitmapFactory.Options sampled(BitmapFactory.Options bounds, int target) {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = 1;
    while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > target)
      options.inSampleSize *= 2;
    return options;
  }

  static InputStream open(ContentResolver resolver, Uri uri) throws IOException {
    return bounded(resolver.openInputStream(uri), null);
  }

  static InputStream open(ContentResolver resolver, Uri uri, CancellationSignal cancellation)
      throws IOException {
    if (cancellation == null) return open(resolver, uri);
    cancellation.throwIfCanceled();
    AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r", cancellation);
    if (descriptor == null) throw new IOException("Image is not readable");
    InputStream source;
    try {
      source = descriptor.createInputStream();
    } catch (IOException | RuntimeException | Error error) {
      descriptor.close();
      throw error;
    }
    return bounded(source, cancellation);
  }

  private static InputStream bounded(InputStream source, CancellationSignal cancellation)
      throws IOException {
    if (source == null) throw new IOException("Image is not readable");
    return new FilterInputStream(source) {
      long left = 64L * 1024 * 1024;

      @Override
      public int read() throws IOException {
        if (cancellation != null) cancellation.throwIfCanceled();
        if (left <= 0) {
          if (in.read() < 0) return -1;
          throw new IOException("Image size limit");
        }
        int n = super.read();
        if (n >= 0) left--;
        return n;
      }

      @Override
      public int read(byte[] b, int offset, int length) throws IOException {
        if (cancellation != null) cancellation.throwIfCanceled();
        if (length == 0) return 0;
        if (left <= 0) {
          if (in.read() < 0) return -1;
          throw new IOException("Image size limit");
        }
        int n = in.read(b, offset, (int) Math.min(length, left));
        if (n > 0) left -= n;
        return n;
      }
    };
  }

  static BitmapFactory.Options bounds(ContentResolver resolver, Uri uri) throws IOException {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    try (InputStream input = open(resolver, uri)) {
      BitmapFactory.decodeStream(input, null, options);
    }
    if (!validBounds(options)) throw new IOException("Unsupported or invalid image");
    return options;
  }

  static Bitmap decode(ContentResolver resolver, Uri uri, BitmapFactory.Options bounds, int target)
      throws IOException {
    BitmapFactory.Options options = sampled(bounds, target);
    try (InputStream input = open(resolver, uri)) {
      Bitmap image = BitmapFactory.decodeStream(input, null, options);
      if (image == null) throw new IOException("Image decoding failed");
      return image;
    }
  }

  static boolean validate(ContentResolver resolver, Uri uri, BitmapFactory.Options bounds) {
    try {
      Bitmap image = decode(resolver, uri, bounds, 128);
      image.recycle();
      return true;
    } catch (IOException | RuntimeException | OutOfMemoryError error) {
      return false;
    }
  }

  static byte[] render(ContentResolver resolver, Uri uri, boolean full) throws IOException {
    BitmapFactory.Options bounds = bounds(resolver, uri);
    int target = full ? 2048 : 128;
    Bitmap source = decode(resolver, uri, bounds, target), result = null, square = null;
    try {
      if (full) {
        float factor =
            Math.min(1f, target / (float) Math.max(source.getWidth(), source.getHeight()));
        result =
            Bitmap.createScaledBitmap(
                source,
                Math.max(1, Math.round(source.getWidth() * factor)),
                Math.max(1, Math.round(source.getHeight() * factor)),
                true);
      } else {
        int side = Math.min(source.getWidth(), source.getHeight());
        square =
            Bitmap.createBitmap(
                source,
                (source.getWidth() - side) / 2,
                (source.getHeight() - side) / 2,
                side,
                side);
        result = Bitmap.createScaledBitmap(square, 128, 128, true);
      }
      try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        if (!result.compress(
            full ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.WEBP, full ? 100 : 82, output))
          throw new IOException("Image encoding failed");
        return output.toByteArray();
      }
    } finally {
      if (result != null && result != source) result.recycle();
      if (square != null && square != source && square != result) square.recycle();
      source.recycle();
    }
  }
}
