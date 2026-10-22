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
                                                "INSFAV-settings-encrypted.json"),
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
    LinearLayout layout = new LinearLayout(activity);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(32, 8, 32, 8);
    layout.setBackgroundColor(android.graphics.Color.BLACK);
    EditText first = input(confirm ? "Password (at least 8 characters)" : "Backup password");
    layout.addView(first);
    EditText second = input("Confirm password");
    if (confirm) layout.addView(second);
    boolean secure =
        (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    AlertDialog dialog =
        new AlertDialog.Builder(activity)
            .setTitle(
                t(
                    confirm
                        ? "Export All Settings"
                        : originalImport
                            ? "Import settings from original INSFAV"
                            : "Import All Settings"))
            .setMessage(
                t(
                    "Encrypted settings and cookies only. Favorites and account lists are excluded."
                        + " Keep the password separately."))
            .setView(layout)
            .setPositiveButton(t("Continue"), null)
            .setNegativeButton(t("Cancel"), null)
            .create();
    dialog.setOnDismissListener(
        d -> {
          first.setText("");
          second.setText("");
          if (!secure) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        });
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      char[] pass = first.getText().toString().toCharArray();
                      if (pass.length < 8
                          || confirm
                              && !first.getText().toString().equals(second.getText().toString())) {
                        Arrays.fill(pass, '\0');
                        first.setError(t("Use matching passwords of at least 8 characters."));
                        return;
                      }
                      dialog.dismiss();
                      action.run(pass);
                    }));
    dialog.show();
  }

  private EditText input(String hint) {
    EditText x = new EditText(activity);
    x.setHint(t(hint));
    x.setTextColor(android.graphics.Color.WHITE);
    x.setHintTextColor(0xff888888);
    x.setSingleLine(true);
    x.setInputType(129);
    x.setSaveEnabled(false);
    x.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    x.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    return x;
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
                              "Settings import failed: wrong password, incompatible backup or"
                                  + " active retrieval. Existing settings were preserved."));
                    } finally {
                      Arrays.fill(pass, '\0');
                    }
                  }));
    return true;
  }
}
