package com.instagram.insfav;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/**
 * Reuses bounded existing fixed-vocabulary logs; no extra per-download disk write or account IDs.
 */
final class OperationHistory {
  static JSONArray read(Context context) throws IOException {
    Set<String> codes =
        new HashSet<>(
            Arrays.asList(
                "success",
                "failed",
                "interrupted",
                "SUCCESS",
                "FAILED",
                "WEBVIEW_TIMEOUT",
                "PROFILE_PAGE_UNAVAILABLE",
                "PROFILE_IMAGE_URL_MISSING",
                "PROFILE_PROCESSING_ERROR",
                "MEMORY_LIMIT",
                "DATABASE_ERROR",
                "INITIALIZATION_FAILED",
                "IMAGE_MEMORY_LIMIT",
                "RATE_LIMITED",
                "REQUEST_CANCELLED",
                "STARTED",
                "OUTPUT_EXISTING_PRESERVED",
                "PERMISSION_LOST",
                "INDEX_SNAPSHOT_FAILED",
                "SESSION_VERIFIED",
                "SESSION_UNVERIFIED",
                "SESSION_UNKNOWN",
                "UNEXPECTED_ARGUMENT",
                "UNEXPECTED_STATE",
                "UNEXPECTED_NULL",
                "UNEXPECTED_MEMORY",
                "UNEXPECTED_OTHER"));
    List<String> lines = new ArrayList<>();
    for (String name :
        new String[] {
          "diagnostics.1.txt",
          "diagnostics.txt",
          "fetcher-diagnostics.previous.txt",
          "fetcher-diagnostics.txt",
          "bootstrap-failure.txt"
        }) {
      File file = new File(context.getFilesDir(), name);
      if (!file.isFile()) continue;
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null)
          if (line.matches("[0-9]{10,} [A-Za-z_]+ [A-Za-z_0-9:]+(?: [0-9]+)?"))
            if (codes.contains(line.split(" ")[2])) lines.add(line);
      }
    }
    lines.sort(
        Comparator.comparingLong(
                (String line) -> Long.parseLong(line.substring(0, line.indexOf(' '))))
            .reversed());
    return new JSONArray(lines.subList(0, Math.min(200, lines.size())));
  }

  private OperationHistory() {}
}
