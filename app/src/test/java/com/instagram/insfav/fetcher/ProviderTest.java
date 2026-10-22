package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import javax.net.ssl.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class ProviderTest {
  static final String IMAGE = "https://scontent.example.cdninstagram.com/v/photo.jpg";

  static String page(String username, String flag) {
    return "<script type='application/json'>{\"user\":{\"username\":\""
        + username
        + "\",\"id\":\"123\",\"has_anonymous_profile_picture\":"
        + flag
        + ",\"profile_pic_url\":\""
        + IMAGE
        + "\"}}</script>";
  }

  @Test
  public void acceptsOnlyBoundExplicitNonDefaultPhoto() throws Exception {
    assertEquals(
        IMAGE, PublicProfileProvider.photo(page("Example", "false"), "example").toString());
  }

  @Test
  public void rejectsOgImageWrongAccountAndAnonymous() throws Exception {
    for (String html :
        new String[] {
          "<meta property='og:image' content='" + IMAGE + "'>",
          page("other", "false"),
          page("example", "true"),
          page("example", "null"),
          page("example", "\"false\"")
        }) {
      try {
        PublicProfileProvider.photo(html, "example");
        fail();
      } catch (ProfileProvider.Failure failure) {
        assertEquals("PROFILE_IMAGE_URL_MISSING", failure.code);
      }
    }
  }

  @Test
  public void sourceAndRedirectPolicy() throws Exception {
    for (String bad :
        new String[] {
          "http://a.cdninstagram.com/x",
          "https://evilcdninstagram.com/x",
          "https://cdninstagram.com.evil.test/x",
          "https://user@a.cdninstagram.com/x",
          "https://a.cdninstagram.com:444/x",
          "https://127.0.0.1/x"
        }) assertFalse(PublicProfileProvider.trusted(new URL(bad), true));
    assertTrue(PublicProfileProvider.trusted(new URL(IMAGE), true));
    assertFalse(PublicProfileProvider.trusted(new URL("https://evil.instagram.com/"), false));
  }

  @Test
  public void retryAfterSecondsAndDate() {
    assertEquals(61000, PublicProfileProvider.retryAt("60", 1000));
    assertEquals(60000, PublicProfileProvider.retryAt("Thu, 01 Jan 1970 00:01:00 GMT", 1000));
    assertEquals(61000, PublicProfileProvider.retryAt("invalid", 1000));
  }

  @Test
  public void httpFailuresAndLoginPause() throws Exception {
    for (int status : new int[] {404, 429, 403, 500}) {
      PublicProfileProvider p = new PublicProfileProvider(url -> new Stub(url, status, ""));
      try {
        p.fetch("example");
        fail();
      } catch (ProfileProvider.Failure error) {
        assertEquals(status, error.http);
        assertEquals(status == 429 || status == 403, error.pause);
        if (status == 404) assertEquals("PROFILE_NOT_FOUND_OR_UNAVAILABLE", error.code);
      }
    }
    PublicProfileProvider p =
        new PublicProfileProvider(
            url -> new Stub(url, 200, "<form id='loginForm'>Log in to Instagram</form>"));
    try {
      p.fetch("example");
      fail();
    } catch (ProfileProvider.Failure error) {
      assertTrue(error.pause);
      assertEquals("LOGIN_REQUIRED", error.code);
    }
  }

  @Test
  public void cancellationBeforeFetchDoesNotOpenConnection() throws Exception {
    PublicProfileProvider p =
        new PublicProfileProvider(
            url -> {
              fail("Cancelled provider must not start a new request");
              return null;
            });
    p.cancel();
    try {
      p.fetch("example");
      fail();
    } catch (ProfileProvider.Failure error) {
      assertEquals("REQUEST_CANCELLED", error.code);
    }
  }

  @Test
  public void networkLossDoesNotSucceed() throws Exception {
    PublicProfileProvider p =
        new PublicProfileProvider(
            url -> {
              throw new IOException("offline");
            });
    try {
      p.fetch("example");
      fail();
    } catch (ProfileProvider.Failure error) {
      assertEquals("NETWORK_ERROR", error.code);
    }
  }

  @Test
  public void redirectToLoginAndForeignHostIsRejected() throws Exception {
    for (String location : new String[] {"/accounts/login/", "https://evil.test/x"}) {
      PublicProfileProvider p =
          new PublicProfileProvider(
              url ->
                  new Stub(url, 302, "") {
                    @Override
                    public String getHeaderField(String key) {
                      return key.equals("Location") ? location : null;
                    }
                  });
      try {
        p.fetch("example");
        fail();
      } catch (ProfileProvider.Failure error) {
        assertTrue(
            error.code.equals("LOGIN_OR_REDIRECT_BLOCKED")
                || error.code.equals("LOGIN_REQUIRED")
                || error.code.equals("UNTRUSTED_SOURCE"));
      }
    }
  }

  @Test
  public void successfulPublicResponseUsesExactDiscoveredUrl() throws Exception {
    int[] requests = {0};
    PublicProfileProvider p =
        new PublicProfileProvider(
            url -> {
              requests[0]++;
              if (requests[0] == 1) return new Stub(url, 200, page("example", "false"));
              assertEquals(IMAGE, url.toString());
              return new Stub(url, 200, "pixels") {
                @Override
                public String getContentType() {
                  return "image/jpeg";
                }
              };
            });
    assertEquals("pixels", new String(p.fetch("example"), StandardCharsets.UTF_8));
    assertEquals(2, requests[0]);
  }

  static class Stub extends HttpsURLConnection {
    final int status;
    final byte[] bytes;

    Stub(URL url, int status, String text) {
      super(url);
      this.status = status;
      bytes = text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int getResponseCode() {
      return status;
    }

    @Override
    public String getContentType() {
      return "text/html; charset=utf-8";
    }

    @Override
    public String getHeaderField(String key) {
      return key.equals("Retry-After") ? "60" : null;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public void connect() {}

    @Override
    public void disconnect() {}

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public String getCipherSuite() {
      return "TLS";
    }

    @Override
    public Certificate[] getLocalCertificates() {
      return null;
    }

    @Override
    public Certificate[] getServerCertificates() {
      return null;
    }
  }

  static final String ERROR_PAGE =
      "<script"
          + " type='application/json'>{\"initialRouteInfo\":{\"route\":{\"rootView\":{\"resource\":{\"__dr\":\"PolarisErrorRoot.react\"},\"props\":{\"failure_reason\":null,\"page_type\":\"PROFILE\"}}}}}</script>";

  @Test
  public void routedErrorPageDoesNotProveNonexistentAccount() throws Exception {
    PublicProfileProvider provider =
        new PublicProfileProvider(url -> new Stub(url, 200, ERROR_PAGE));
    try {
      provider.fetch("example");
      fail();
    } catch (ProfileProvider.Failure error) {
      assertEquals("PROFILE_PAGE_UNAVAILABLE", error.code);
      assertEquals(200, error.http);
      assertTrue(error.pause);
    }
  }

  @Test
  public void bundleReferenceAloneIsNotAnErrorPage() throws Exception {
    assertEquals(
        IMAGE,
        PublicProfileProvider.photo(
                "<script>{\"modules\":[\"PolarisErrorRoot.react\"]}</script>"
                    + page("example", "false"),
                "example")
            .toString());
    try {
      PublicProfileProvider.photo(
          "<script>{\"modules\":[\"PolarisErrorRoot.react\"]}</script>", "example");
      fail();
    } catch (ProfileProvider.Failure error) {
      assertEquals("PROFILE_IMAGE_URL_MISSING", error.code);
      assertFalse(error.pause);
    }
  }
}
