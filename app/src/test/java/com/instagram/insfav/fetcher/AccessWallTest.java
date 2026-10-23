package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class AccessWallTest {
  private static final String BUNDLE =
      "<script>{\"modules\":[\"CaptchaProvider\",\"/challenge/\",\"Log in to"
          + " Instagram\"],\"checkpoint_required\":false,\"login_required\":false}</script>";

  @Test
  public void bundledCaptchaNamesAndLoginLinksAreNotWalls() {
    assertEquals(
        "", AccessWall.detect(BUNDLE + "<a href='/accounts/login/'>Log in to Instagram</a>"));
    assertEquals(
        "", AccessWall.detect("<script src='/captcha/provider.js'></script><!-- /challenge/ -->"));
  }

  @Test
  public void authenticatedPageWithCaptchaBundleStillAttestsSession() throws Exception {
    CookieJar cookies =
        CookieJar.parse("sessionid=TEST_SESSION; ds_user_id=123", System.currentTimeMillis());
    String html = BUNDLE + "<script>{\"config\":{\"viewerId\":\"123\"}}</script>";
    PublicProfileProvider provider =
        new PublicProfileProvider(url -> new ProviderTest.Stub(url, 200, html), cookies);
    provider.authenticate();
  }

  @Test
  public void profileDownloadDoesNotStopAtIncidentalCaptchaStrings() throws Exception {
    int[] requests = {0};
    PublicProfileProvider provider =
        new PublicProfileProvider(
            url -> {
              if (++requests[0] == 1)
                return new ProviderTest.Stub(
                    url, 200, BUNDLE + ProviderTest.page("example", "false"));
              return new ProviderTest.Stub(url, 200, "pixels") {
                @Override
                public String getContentType() {
                  return "image/jpeg";
                }
              };
            });
    assertEquals("pixels", new String(provider.fetch("example"), StandardCharsets.UTF_8));
    assertEquals(2, requests[0]);
  }

  @Test
  public void explicitServerChallengeAndVisibleFormsStillPause() throws Exception {
    for (String html :
        new String[] {
          "<script>{\"checkpoint_required\" : true}</script>",
          "<script>{\"nested\":[{\"challenge_required\":true}]}</script>",
          "<script>{\"status\":\"fail\",\"message\":\"challenge_required\"}</script>",
          "<form method='post' action='/challenge/verify/'></form>",
          "<form id=checkpointForm></form>"
        }) {
      assertEquals("CHALLENGE_REQUIRED", AccessWall.detect(html));
      PublicProfileProvider provider =
          new PublicProfileProvider(url -> new ProviderTest.Stub(url, 200, html));
      try {
        provider.fetch("example");
        fail();
      } catch (ProfileProvider.Failure e) {
        assertEquals("CHALLENGE_REQUIRED", e.code);
        assertTrue(e.pause);
        assertEquals(200, e.http);
      }
    }
  }

  @Test
  public void loginFormsAndExplicitFlagsStillRejectSessions() {
    for (String html :
        new String[] {
          "<form id='loginForm'></form>",
          "<form action=/accounts/login/ method=post></form>",
          "<script>{\"login_required\" : true}</script>",
          "<script>{\"status\":\"fail\",\"message\":\"login_required\"}</script>"
        }) assertEquals("LOGIN_REQUIRED", AccessWall.detect(html));
    assertEquals(
        "CHALLENGE_REQUIRED",
        AccessWall.detect(
            "<form id=loginForm></form><script>{\"checkpoint_required\":true}</script>"));
  }

  @Test
  public void removingFalseChallengeNeverInventsAuthenticationOrPhoto() throws Exception {
    CookieJar cookies =
        CookieJar.parse("sessionid=TEST_SESSION; ds_user_id=123", System.currentTimeMillis());
    PublicProfileProvider provider =
        new PublicProfileProvider(url -> new ProviderTest.Stub(url, 200, BUNDLE), cookies);
    try {
      provider.authenticate();
      fail();
    } catch (ProfileProvider.Failure e) {
      assertEquals("COOKIE_AUTH_UNVERIFIED", e.code);
      assertEquals(200, e.http);
    }
    try {
      PublicProfileProvider.photo(BUNDLE, "example");
      fail();
    } catch (ProfileProvider.Failure e) {
      assertEquals("PROFILE_IMAGE_URL_MISSING", e.code);
    }
  }
}
