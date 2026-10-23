package com.instagram.insfav;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** One sequential SAF metadata cursor, bounded validation workers and durable 512-row batches. */
final class ThumbnailScanner implements AutoCloseable {
  static final int BATCH_SIZE = 512, WORKERS = 2;

  interface Progress {
    void update(int processed, int total) throws Exception;
  }

  private final ContentResolver resolver;
  final ThumbnailScanIndex index;
  private final int workers;
  private final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
  int inspected, reused, processed, total, metadataQueries;
  long queryNanos, listingNanos;
  final ImageCodec.Timings timings = new ImageCodec.Timings();

  ThumbnailScanner(Context context, File root) {
    this(context, root, WORKERS);
  }

  ThumbnailScanner(Context context, File root, int workers) {
    if (workers < 1 || workers > 4) throw new IllegalArgumentException("Scan worker limit");
    resolver = context.getContentResolver();
    index = new ThumbnailScanIndex(context, root);
    this.workers = workers;
  }

  ThumbnailPolicy.Catalog scan(
      Uri tree, String directory, JSONObject previous, CancellationSignal cancel, Progress progress)
      throws Exception {
    index.begin(tree + "\n" + directory);
    ExecutorService pool =
        Executors.newFixedThreadPool(
            workers,
            r -> {
              Thread t = new Thread(r, "thumbnail-scan");
              t.setDaemon(true);
              return t;
            });
    String[] columns = {
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_SIZE,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED,
      DocumentsContract.Document.COLUMN_MIME_TYPE
    };
    try {
      metadataQueries++;
      long queryStarted = System.nanoTime();
      try (Cursor cursor =
          resolver.query(
              DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory),
              columns,
              null,
              null,
              null,
              cancel)) {
        if (cursor == null)
          throw new IOException(
              "Folder could not be read. Completed scan checkpoints are preserved.");
        total = cursor.getCount();
        queryNanos += System.nanoTime() - queryStarted;
        progress.update(0, total);
        List<ThumbnailScanIndex.Entry> batch = new ArrayList<>(BATCH_SIZE);
        while (advance(cursor)) {
          cancel.throwIfCanceled();
          if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
          String name = cursor.getString(1);
          if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(4))
              && ThumbnailPolicy.supported(name)) {
            String id = cursor.getString(0);
            if (id == null || id.isEmpty())
              throw new IOException("Provider returned an invalid document identifier.");
            batch.add(
                new ThumbnailScanIndex.Entry(
                    id,
                    name,
                    DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                    cursor.isNull(2) ? -1 : cursor.getLong(2),
                    cursor.isNull(3) ? 0 : cursor.getLong(3)));
          }
          processed++;
          if (batch.size() == BATCH_SIZE) {
            process(batch, previous, cancel, pool);
            batch.clear();
          }
          if (processed % BATCH_SIZE == 0) progress.update(processed, total);
        }
        process(batch, previous, cancel, pool);
        // Cloud providers can return an incomplete cursor while still loading.
        android.os.Bundle extras = cursor.getExtras();
        if (extras != null
            && (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)
                || extras.containsKey(DocumentsContract.EXTRA_ERROR)))
          throw new IOException("Folder listing is incomplete. Retry to resume saved checkpoints.");
        cancel.throwIfCanceled();
        progress.update(processed, total);
      }
      return index.catalog();
    } finally {
      pool.shutdownNow();
      try {
        pool.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void process(
      List<ThumbnailScanIndex.Entry> batch,
      JSONObject previous,
      CancellationSignal cancel,
      ExecutorService pool)
      throws Exception {
    if (batch.isEmpty()) return;
    Map<String, ThumbnailScanIndex.Entry> saved = index.lookup(batch);
    List<ThumbnailScanIndex.Entry> done = new ArrayList<>(batch.size());
    List<Future<ThumbnailScanIndex.Entry>> pending = new ArrayList<>();
    for (ThumbnailScanIndex.Entry e : batch) {
      ThumbnailScanIndex.Entry old = saved.get(e.id);
      if (e.matches(old)) {
        e.reuse(old);
        done.add(e);
        reused++;
        continue;
      }
      JSONObject p =
          previous == null ? null : previous.optJSONObject(ThumbnailPolicy.username(e.name));
      if (old == null
          && p != null
          && p.optBoolean("readable")
          && p.optInt("width") > 0
          && p.optInt("height") > 0
          && e.size >= 0
          && e.modified > 0
          && e.size == p.optLong("size", -1)
          && e.modified == p.optLong("modified")
          && e.name.equals(p.optString("name"))
          && e.uri.equals(p.optString("uri"))) {
        e.width = p.optInt("width");
        e.height = p.optInt("height");
        e.readable = true;
        done.add(e);
        reused++;
        continue;
      }
      pending.add(
          pool.submit(
              () -> {
                if (cancel.isCanceled() || Thread.currentThread().isInterrupted()) return null;
                int count = active.incrementAndGet();
                peak.accumulateAndGet(count, Math::max);
                try {
                  if (e.size > 64L * 1024 * 1024) {
                    e.readable = false;
                    return e;
                  }
                  ImageCodec.Inspection result =
                      ImageCodec.inspect(resolver, Uri.parse(e.uri), cancel, timings);
                  e.width = result.width;
                  e.height = result.height;
                  e.readable = result.readable;
                  return e;
                } catch (FileNotFoundException disappeared) {
                  // A stale listing or temporarily unavailable file must be retried on the next
                  // scan.
                  e.readable = false;
                  e.reusable = false;
                  return e;
                } catch (OutOfMemoryError memory) {
                  cancel.cancel();
                  throw memory;
                } finally {
                  active.decrementAndGet();
                }
              }));
    }
    Throwable failure = null;
    try {
      for (Future<ThumbnailScanIndex.Entry> f : pending)
        try {
          ThumbnailScanIndex.Entry result = f.get();
          if (result != null) {
            done.add(result);
            inspected++;
          }
        } catch (ExecutionException e) {
          if (failure == null || e.getCause() instanceof Error) failure = e.getCause();
        }
    } finally {
      index.save(done);
    }
    if (failure instanceof Error) throw (Error) failure;
    if (failure instanceof Exception) throw (Exception) failure;
    cancel.throwIfCanceled();
  }

  private boolean advance(Cursor cursor) {
    long started = System.nanoTime();
    try {
      return cursor.moveToNext();
    } finally {
      listingNanos += System.nanoTime() - started;
    }
  }

  JSONObject metrics() throws JSONException {
    return new JSONObject()
        .put("metadataQueries", metadataQueries)
        .put("inspected", inspected)
        .put("reused", reused)
        .put("workers", workers)
        .put("peakValidations", peak.get())
        .put("batchSize", BATCH_SIZE)
        .put("safQueryNanos", queryNanos)
        .put("safCursorNanos", listingNanos)
        .put("sourceOpenNanos", timings.opens.get())
        .put("validationDecodeNanos", timings.decodes.get())
        .put("sqliteTransactionNanos", index.transactionNanos)
        .put("transactions", index.transactions)
        .put("resumed", index.resumed);
  }

  @Override
  public void close() {
    index.close();
  }
}
