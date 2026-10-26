package com.instagram.insfav.fetcher;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import java.io.*;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Best-effort local side effect of an already validated remote HD result. No network or queue work. */
final class HdThumbnailWriter {
    private static final Set<String> active = ConcurrentHashMap.newKeySet();

    static boolean save(Context context, Fetcher.Host host, String user, byte[] source) {
        if (source == null) return false;
        String key = null;
        boolean acquired = false;
        try {
            String tree = host.tree();
            key = tree + "\n" + host.directory() + "\n" + user;
            if (!active.add(key)) return false;
            acquired = true;
            OutputFolder folder = new OutputFolder(context, tree);
            String filename = Identity.outputFilename(user);
            Uri uri;
            if (folder.valid(filename)) {
                byte[] thumbnail = encode(source);
                if (thumbnail == null) return false;
                uri = folder.commit(filename, thumbnail, true);
                if (!folder.lastWriteCreated) return false;
            } else {
                uri = folder.commitMissingHd(filename, source);
            }
            if (uri == null) return false;
            host.imageWritten(user, uri, tree);
            host.flushImages();
            return true;
        } catch (Exception | OutOfMemoryError ignored) {
            // Permission, provider and encoding failures must never affect the viewer or fetcher.
            return false;
        } finally {
            Arrays.fill(source, (byte) 0);
            if (acquired) active.remove(key);
        }
    }

    static byte[] encode(byte[] input) throws IOException {
        if (input == null || input.length == 0 || input.length > 8 * 1024 * 1024
                || !ThumbnailCodec.completeContainer(input)) throw new IOException("IMAGE_CONTAINER_INVALID");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(input, 0, input.length, bounds);
        int width = bounds.outWidth, height = bounds.outHeight;
        if (width < 1 || height < 1 || width > 16384 || height > 16384
                || (long) width * height > 40000000
                || !("image/jpeg".equals(bounds.outMimeType) || "image/png".equals(bounds.outMimeType)
                || "image/webp".equals(bounds.outMimeType))) throw new IOException("IMAGE_REJECTED");
        // A small remote fallback remains viewable, but cannot produce a 256px thumbnail without enlargement.
        if (Math.min(width, height) < ThumbnailCodec.TARGET_SIZE) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.min(width, height) / (options.inSampleSize * 2) >= ThumbnailCodec.TARGET_SIZE)
            options.inSampleSize *= 2;
        Bitmap source = BitmapFactory.decodeByteArray(input, 0, input.length, options);
        if (source == null) throw new IOException("IMAGE_DECODE_FAILED");
        Bitmap result = null;
        try {
            int side = Math.min(source.getWidth(), source.getHeight());
            if (side < ThumbnailCodec.TARGET_SIZE) return null;
            int left = (source.getWidth() - side) / 2, top = (source.getHeight() - side) / 2;
            result = Bitmap.createBitmap(ThumbnailCodec.TARGET_SIZE, ThumbnailCodec.TARGET_SIZE, Bitmap.Config.ARGB_8888);
            new Canvas(result).drawBitmap(source, new Rect(left, top, left + side, top + side),
                    new Rect(0, 0, ThumbnailCodec.TARGET_SIZE, ThumbnailCodec.TARGET_SIZE), new Paint(Paint.FILTER_BITMAP_FLAG));
            if (!ThumbnailCodec.visible(result)) throw new IOException("IMAGE_REJECTED");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!result.compress(Bitmap.CompressFormat.WEBP, 78, output)) throw new IOException("WEBP_ENCODE_FAILED");
            byte[] bytes = output.toByteArray();
            if (!ThumbnailCodec.valid(bytes)) throw new IOException("WEBP_VERIFY_FAILED");
            return bytes;
        } finally {
            if (result != null) result.recycle();
            source.recycle();
        }
    }
    private HdThumbnailWriter() {}
}
