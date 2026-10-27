package com.instagram.insfav.fetcher;

import android.content.Context;
import android.security.keystore.*;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM secrets at rest; the production key never leaves Android Keystore. */
final class CookieVault {
  interface Keys {
    SecretKey get() throws GeneralSecurityException, IOException;
  }

  private static final String ALIAS = "insfav.cookie.aes.v1";
  private final AtomicFile file;
  private final Keys keys;

  CookieVault(Context context) {
    this(
        context,
        () -> {
          KeyStore store = KeyStore.getInstance("AndroidKeyStore");
          store.load(null);
          if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(
                new KeyGenParameterSpec.Builder(
                        ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build());
            generator.generateKey();
          }
          return (SecretKey) store.getKey(ALIAS, null);
        });
  }

  CookieVault(Context context, Keys keys) { this(context, keys, "fetcher-cookies.enc"); }

  CookieVault(Context context, Keys keys, String name) {
    file = new AtomicFile(new File(context.getFilesDir(), name));
    this.keys = keys;
  }

  static CookieVault namedBrowser(Context c, String name) { return new CookieVault(c, new CookieVault(c).keys, name); }
  static CookieVault browser(Context c) {
        CookieVault original = new CookieVault(c);
        return new CookieVault(c, original.keys, "instagram-webview-cookies.enc");
    }
    private SecretKey key() throws GeneralSecurityException, IOException {
        synchronized(CookieVault.class) { return keys.get(); }
    }
    synchronized boolean configured() {
    return file.getBaseFile().isFile();
  }

  synchronized void save(CookieJar jar) throws Exception {
    byte[] plain = jar.encode().getBytes(StandardCharsets.UTF_8);
    FileOutputStream output = null;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key());
      cipher.updateAAD("INSFAV_COOKIE_V1".getBytes(StandardCharsets.US_ASCII));
      byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plain);
      output = file.startWrite();
      output.write(1);
      output.write(iv.length);
      output.write(iv);
      output.write(encrypted);
      file.finishWrite(output);
      output = null;
    } finally {
      Arrays.fill(plain, (byte) 0);
      if (output != null) file.failWrite(output);
    }
  }

  synchronized String stored() throws Exception {
    byte[] plain = null;
    try {
      byte[] bytes = file.readFully();
      if (bytes.length < 30 || bytes.length > 131072 || bytes[0] != 1 || bytes[1] != 12)
        throw new GeneralSecurityException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          key(),
          new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 2, 14)));
      cipher.updateAAD("INSFAV_COOKIE_V1".getBytes(StandardCharsets.US_ASCII));
      plain = cipher.doFinal(bytes, 14, bytes.length - 14);
      String value = new String(plain, StandardCharsets.UTF_8);
      CookieJar.decode(value);
      return value;
    } finally {
      if (plain != null) Arrays.fill(plain, (byte) 0);
    }
  }

  synchronized CookieJar load() throws ProfileProvider.Failure {
    try {
      CookieJar jar = CookieJar.decode(stored());
      if (jar.value("sessionid", System.currentTimeMillis()).isEmpty())
        throw new ProfileProvider.Failure("COOKIE_EXPIRED");
      return jar;
    } catch (ProfileProvider.Failure e) {
      throw e;
    } catch (FileNotFoundException e) {
      throw new ProfileProvider.Failure("COOKIE_REQUIRED");
    } catch (Exception e) {
      throw new ProfileProvider.Failure("COOKIE_DECRYPT_FAILED");
    }
  }

  synchronized void remove() {
    file.delete();
  }
}
