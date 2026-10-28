package com.instagram.insfav.fetcher;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** Passwords stay in native controls and never enter the WebView bridge, logs or diagnostics. */
public final class SettingsBackupUi {
  private String t(String value) {
    return com.instagram.insfav.UiText.text(activity, value);
  }

  public interface Host {
    String export(char[] password) throws Exception;

    JSONObject inspect(String raw, char[] password) throws Exception;

    default JSONObject inspectOriginal(String raw, char[] password) throws Exception {
      throw new IOException("Unsupported migration");
    }

    void apply(JSONObject data) throws Exception;

    void message(String text);
  }

  private final Activity activity;
  private final Executor io;
  private final Host host;
  private String encryptedExport;
  private boolean originalImport;
  private static final int SAVE = 61, OPEN = 62;

  public SettingsBackupUi(Activity a, Executor io, Host host) {
    activity = a;
    this.io = io;
    this.host = host;
  }

  public void startOriginal() {
    originalImport = true;
    start(false, true);
  }

  public void start(boolean exporting) {
    start(exporting, false);
  }

  private void start(boolean exporting, boolean original) {
    originalImport = original;
    activity.runOnUiThread(
        () -> {
          if (exporting)
            password(
                true,
                pass ->
                    io.execute(
                        () -> {
                          try {
                            encryptedExport = host.export(pass);
                            activity.runOnUiThread(
                                () ->
                                    activity.startActivityForResult(
                                        new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                            .setType("application/json")
                                            .addCategory(Intent.CATEGORY_OPENABLE)
                                            .putExtra(
                                                Intent.EXTRA_TITLE,
                                                "INSFAV-Plus-settings-encrypted.json"),
                                        SAVE));
                          } catch (Exception e) {
                            host.message(
                                t("Settings export failed. Existing settings are unchanged."));
                          } finally {
                            Arrays.fill(pass, '\0');
                          }
                        }));
          else
            activity.startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE),
                OPEN);
        });
  }

  private interface PasswordAction {
    void run(char[] password);
  }

 private void password(boolean confirm, PasswordAction action) {
  BackupPinUi.show(activity, t(confirm ? "Export All Settings" : originalImport ? "Import settings from original INSFAV" : "Import All Settings"), confirm, action::run);
 }
  public boolean result(int request, int result, Intent intent) {
    if (request != SAVE && request != OPEN) return false;
    if (result != Activity.RESULT_OK || intent == null || intent.getData() == null) {
      encryptedExport = null;
      return true;
    }
    Uri uri = intent.getData();
    if (request == SAVE) {
      String data = encryptedExport;
      encryptedExport = null;
      io.execute(
          () -> {
            try (OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt")) {
              if (data == null || out == null) throw new IOException();
              out.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
              host.message(t("Encrypted settings exported."));
            } catch (Exception e) {
              host.message(t("Settings export failed."));
            }
          });
    } else
      password(
          false,
          pass ->
              io.execute(
                  () -> {
                    try {
                      String raw;
                      try (InputStream in = activity.getContentResolver().openInputStream(uri);
                          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        if (in == null) throw new IOException();
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) != -1) {
                          if (out.size() + n > 1024 * 1024) throw new IOException();
                          out.write(b, 0, n);
                        }
                        raw = out.toString("UTF-8");
                      }
                      JSONObject data =
                          originalImport
                              ? host.inspectOriginal(raw, pass)
                              : host.inspect(raw, pass);
                      host.apply(data);
                      host.message(
                          t(
                              "Settings restored. Retrieval is stopped; review the folder and tap"
                                  + " Resume when ready."));
                    } catch (Exception e) {
                      host.message(
                          t(
                              "Settings import failed: wrong password, incompatible backup, active"
                                  + " retrieval, or storage error. Review settings and"
                                  + " diagnostics."));
                    } finally {
                      Arrays.fill(pass, '\0');
                    }
                  }));
    return true;
  }
}
