package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.crypto.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class CookieTest {
  @Test
  public void parsesHeaderAndRestrictsEveryTarget() throws Exception {
    CookieJar jar =
        CookieJar.parse(
            "Cookie: sessionid=TEST_SESSION; ds_user_id=123; csrftoken=TEST_CSRF", 1000);
    assertTrue(
        jar.header(new URL("https://www.instagram.com/example/"), 1000)
            .contains("sessionid=TEST_SESSION"));
    for (String target :
        new String[] {
          "http://www.instagram.com/",
          "https://evil.instagram.com/",
          "https://www.instagram.com.evil.test/",
          "https://scontent.example.cdninstagram.com/x",
          "https://www.instagram.com:444/",
          "https://u@www.instagram.com/"
        }) assertEquals("", jar.header(new URL(target), 1000));
    assertFalse(jar.toString().contains("TEST_SESSION"));
    assertFalse(jar.entries.get(0).toString().contains("TEST_SESSION"));
  }

  @Test
  public void netscapeHonorsDomainPathExpiryAndHttpOnly() throws Exception {
    String raw =
        "# Netscape HTTP Cookie File\n"
            + "#HttpOnly_.instagram.com\tTRUE\t/\tTRUE\t2000000000\tsessionid\tTEST_SESSION\n"
            + ".evil.test\tTRUE\t/\tTRUE\t0\tother\tDO_NOT_SEND\n"
            + ".instagram.com\tTRUE\t/private\tFALSE\t0\tpath_cookie\tTEST_PATH\n";
    CookieJar jar = CookieJar.parse(raw, 1000);
    assertFalse(
        jar.header(new URL("https://www.instagram.com/example/"), 1000).contains("path_cookie"));
    assertTrue(
        jar.header(new URL("https://www.instagram.com/private/a"), 1000).contains("path_cookie"));
    assertFalse(jar.header(new URL("https://www.instagram.com/"), 1000).contains("DO_NOT_SEND"));
    try {
      CookieJar.parse(raw, 2000000001000L);
      fail();
    } catch (ProfileProvider.Failure e) {
      assertEquals("COOKIE_EXPIRED", e.code);
    }
  }

  @Test
  public void rejectsInjectionMissingAmbiguousAndOversizedInput() throws Exception {
    for (String raw :
        new String[] {
          "sessionid=TEST\r\nX-Injected: yes",
          "csrftoken=TEST",
          "sessionid=ONE; sessionid=TWO",
          "sessionid=bad value",
          "sessionid=TEST; X\r\n=Y",
          "x".repeat(CookieJar.MAX_INPUT + 1)
        }) {
      try {
        CookieJar.parse(raw, 1000);
        fail();
      } catch (ProfileProvider.Failure expected) {
      }
    }
  }

  @Test
  public void preservesQuotedBrowserCookieEscapes() throws Exception {
    String raw = "sessionid=TEST_SESSION; rur=\"REGION\\054123\\0542000000000:TEST\"";
    CookieJar jar = CookieJar.parse(raw, 1000);
    assertEquals(raw, jar.header(new URL("https://www.instagram.com/example/"), 1000));
    String netscape =
        ".instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tTEST_SESSION\n"
            + ".instagram.com\tTRUE\t/\tTRUE\t0\trur\t\"REGION\\054123\"";
    assertTrue(
        CookieJar.parse(netscape, 1000)
            .header(new URL("https://www.instagram.com/"), 1000)
            .contains("rur=\"REGION\\054123\""));
    for (String bad :
        new String[] {
          "sessionid=TEST; rur=\"open",
          "sessionid=TEST; rur=\"bad\"quote\"",
          "sessionid=TEST; rur=\"bad\\\""
        }) {
      try {
        CookieJar.parse(bad, 1000);
        fail();
      } catch (ProfileProvider.Failure expected) {
      }
    }
  }

  @Test
  public void authenticatedViewerRequiresIdentityAndRejectsRecommendations() {
    assertTrue(
        PublicProfileProvider.authenticated(
            "<script>{\"config\":{\"viewerId\":\"123\"}}</script>", "123"));
    assertFalse(
        PublicProfileProvider.authenticated(
            "<script>{\"config\":{\"viewerId\":\"999\"}}</script>", "123"));
    assertFalse(
        PublicProfileProvider.authenticated(
            "<script>{\"user\":{\"id\":\"123\",\"username\":\"example\"}}</script>", "123"));
    assertFalse(
        PublicProfileProvider.authenticated(
            "<script>{\"viewerId\":\"123\",\"is_logged_in\":false}</script>", "123"));
  }

  @Test
  public void encryptedRoundTripAndTamperRejection() throws Exception {
    android.content.Context c = RuntimeEnvironment.getApplication();
    SecretKey key = KeyGenerator.getInstance("AES").generateKey();
    CookieVault vault = new CookieVault(c, () -> key);
    CookieJar jar =
        CookieJar.parse("sessionid=TEST_SESSION; ds_user_id=123", System.currentTimeMillis());
    vault.save(jar);
    Path path = c.getFilesDir().toPath().resolve("fetcher-cookies.enc");
    byte[] bytes = Files.readAllBytes(path);
    assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("TEST_SESSION"));
    assertEquals("TEST_SESSION", vault.load().value("sessionid", System.currentTimeMillis()));
    bytes[bytes.length - 1] ^= 1;
    Files.write(path, bytes);
    try {
      vault.load();
      fail();
    } catch (ProfileProvider.Failure e) {
      assertEquals("COOKIE_DECRYPT_FAILED", e.code);
    }
    vault.remove();
    assertFalse(vault.configured());
  }

  @Test
  public void authenticatedTransportSendsNoCookiesToImageCdn() throws Exception {
    CookieJar jar =
        CookieJar.parse("sessionid=TEST_SESSION; ds_user_id=123", System.currentTimeMillis());
    int[] requests = {0};
    PublicProfileProvider provider =
        new PublicProfileProvider(
            url -> {
              requests[0]++;
              if (requests[0] == 1)
                return new ProviderTest.Stub(url, 200, ProviderTest.page("example", "false")) {
                  @Override
                  public int getResponseCode() {
                    assertTrue(getRequestProperty("Cookie").contains("TEST_SESSION"));
                    return 200;
                  }
                };
              return new ProviderTest.Stub(url, 200, "pixels") {
                @Override
                public String getContentType() {
                  return "image/jpeg";
                }

                @Override
                public int getResponseCode() {
                  assertNull(getRequestProperty("Cookie"));
                  return 200;
                }
              };
            },
            jar);
    provider.fetch("example");
    assertEquals(2, requests[0]);
  }

  @Test
  public void cookieSetupAndTransferDoNotProveSessionValidity() {
    assertFalse(SessionState.invalid(new ProfileProvider.Failure("COOKIE_WEBVIEW_REJECTED")));
    assertFalse(SessionState.invalid(new ProfileProvider.Failure("COOKIE_WEBVIEW_SETUP_TIMEOUT")));
    assertFalse(SessionState.invalid(new ProfileProvider.Failure("COOKIE_AUTH_UNVERIFIED")));
    assertTrue(SessionState.invalid(new ProfileProvider.Failure("COOKIE_INVALID_EXPIRED")));
    android.content.SharedPreferences p = SessionState.prefs(RuntimeEnvironment.getApplication());
    p.edit().putString("sessionEvidence", "INVALID").commit();
    assertEquals("UNVERIFIED", SessionState.evidence(p));
    p.edit().putString("sessionEvidence", "UNKNOWN").commit();
    new Diagnostics(RuntimeEnvironment.getApplication()).session("SHARED_COOKIES_APPLIED");
    assertEquals("UNKNOWN", SessionState.evidence(p));
  }
}
