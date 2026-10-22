package com.instagram.insfav;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Immutable encoded source chunks. HTTP readers do not create another full-size byte array. */
final class SourceBytes {
  static final SourceBytes EMPTY = new SourceBytes(new ArrayList<>(), 0);
  static final int LIMIT = 64 * 1024 * 1024;
  final List<byte[]> chunks;
  final int length;

  private SourceBytes(List<byte[]> chunks, int length) {
    this.chunks = chunks;
    this.length = length;
  }

  static SourceBytes read(InputStream input) throws IOException {
    return read(input, LIMIT);
  }

  static SourceBytes read(InputStream input, int limit) throws IOException {
    List<byte[]> chunks = new ArrayList<>();
    int total = 0;
    boolean eof = false;
    while (!eof) {
      byte[] chunk = new byte[Math.min(16384, Math.max(1, limit - total + 1))];
      int used = 0;
      while (used < chunk.length) {
        int n = input.read(chunk, used, chunk.length - used);
        if (n < 0) {
          eof = true;
          break;
        }
        if (n == 0) {
          int value = input.read();
          if (value < 0) {
            eof = true;
            break;
          }
          chunk[used++] = (byte) value;
        } else used += n;
        if ((long) total + used > limit) throw new IOException("Image size limit");
      }
      if (used > 0) {
        chunks.add(used == chunk.length ? chunk : Arrays.copyOf(chunk, used));
        total += used;
      }
    }
    return new SourceBytes(chunks, total);
  }

  InputStream open() {
    return new ChunkStream(chunks);
  }

  private static final class ChunkStream extends InputStream {
    List<byte[]> data;
    int chunk, offset;

    ChunkStream(List<byte[]> chunks) {
      data = chunks;
    }

    @Override
    public synchronized int read() {
      if (data == null || chunk >= data.size()) return -1;
      byte[] current = data.get(chunk);
      int value = current[offset++] & 255;
      if (offset == current.length) {
        chunk++;
        offset = 0;
      }
      return value;
    }

    @Override
    public synchronized int read(byte[] target, int start, int count) {
      if (start < 0 || count < 0 || start > target.length - count)
        throw new IndexOutOfBoundsException();
      if (count == 0) return 0;
      if (data == null || chunk >= data.size()) return -1;
      int written = 0;
      while (count > 0 && chunk < data.size()) {
        byte[] current = data.get(chunk);
        int n = Math.min(count, current.length - offset);
        System.arraycopy(current, offset, target, start, n);
        offset += n;
        start += n;
        count -= n;
        written += n;
        if (offset == current.length) {
          chunk++;
          offset = 0;
        }
      }
      return written;
    }

    @Override
    public synchronized void close() {
      data = null;
    }
  }
}
