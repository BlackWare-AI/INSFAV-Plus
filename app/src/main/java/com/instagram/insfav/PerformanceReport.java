package com.instagram.insfav;

import org.json.*;

/**
 * Samples and their measurement scope are explicit; absence is never displayed as measured zero.
 */
final class PerformanceReport {
  static JSONObject read(Store store, LocalImages images, JSONObject fetcher) throws Exception {
    JSONObject current = images.snapshot(false),
        scan = current.getJSONObject("scan").optJSONObject("io"),
        out = new JSONObject();
    JSONArray values = new JSONArray();
    JSONObject saved = store.settings.optJSONObject("lastScanMetrics");
    if (scan == null) scan = saved;
    for (String[] field :
        new String[][] {
          {"SAF query", "safQueryNanos"},
          {"Source open", "sourceOpenNanos"},
          {"Validation / decode", "validationDecodeNanos"},
          {"SQLite transactions", "sqliteTransactionNanos"},
          {"JSON snapshot", "jsonWriteNanos"}
        })
      add(
          values,
          field[0],
          scan == null ? null : scan.opt(field[1]),
          "ns",
          "Last scan; decode includes source reading");
    add(
        values,
        "Rescan duration",
        saved == null ? null : saved.opt("elapsedMs"),
        "ms",
        "Last completed scan");
    add(values, "Active WebViews", fetcher.opt("activeViews"), "count", "Current pool");
    add(
        values,
        "Fetcher workers",
        fetcher.opt("effectiveViews"),
        "count",
        "Current effective limit");
    long pss = android.os.Debug.getPss();
    add(values, "Process PSS", pss > 0 ? pss : null, "KiB", "Android process sample");
    Runtime runtime = Runtime.getRuntime();
    add(
        values,
        "Java heap used",
        runtime.totalMemory() - runtime.freeMemory(),
        "bytes",
        "Current Java heap; not total native RAM");
    JSONObject io = current.getJSONObject("imageIO");
    long reads = io.optLong("sourceReads");
    add(
        values,
        "Average native image read",
        reads > 0 ? io.optLong("readNanos") / reads : null,
        "ns",
        "Source I/O; not browser decode");
    return out.put("metrics", values)
        .put("previousScan", store.settings.optJSONObject("previousScanMetrics"))
        .put("lastScan", saved)
        .put("indexWrites", store.writeMetrics());
  }

  private static void add(JSONArray target, String label, Object value, String unit, String scope)
      throws JSONException {
    target.put(
        new JSONObject()
            .put("label", label)
            .put("value", value == null ? JSONObject.NULL : value)
            .put("measured", value != null && value != JSONObject.NULL)
            .put("unit", unit)
            .put("scope", scope));
  }

  private PerformanceReport() {}
}
