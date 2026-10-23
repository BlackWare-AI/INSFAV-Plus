package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;

import android.content.Context;
import android.webkit.*;
import java.util.concurrent.CountDownLatch;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class CookieApplicationTest {
  static class Manager extends CookieManager {
    Boolean accepted = true;
    boolean broken;

    public void setAcceptCookie(boolean value) {}

    public boolean acceptCookie() {
      return true;
    }

    public void setCookie(String u, String v) {}

    public void setCookie(String u, String v, ValueCallback<Boolean> callback) {
      if (broken) throw new IllegalStateException("fixture");
      callback.onReceiveValue(accepted);
    }

    public String getCookie(String u) {
      return "";
    }

    public void removeSessionCookie() {}

    public void removeSessionCookies(ValueCallback<Boolean> c) {}

    public void removeAllCookie() {}

    public void removeAllCookies(ValueCallback<Boolean> c) {}

    public boolean hasCookies() {
      return false;
    }

    public void removeExpiredCookie() {}

    public void flush() {}

    public void setAcceptThirdPartyCookies(WebView v, boolean a) {}

    public boolean acceptThirdPartyCookies(WebView v) {
      return false;
    }
  }

  WebViewProfileProvider provider() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    SessionState.prefs(c).edit().clear().commit();
    return new WebViewProfileProvider(
        c, CookieJar.parse("sessionid=FIXTURE; ds_user_id=123", System.currentTimeMillis()));
  }

  @Test
  public void acceptedCookiesOnlyCompleteTransferAndDoNotVerifySession() throws Exception {
    WebViewProfileProvider p = provider();
    p.applySessionCookies(new Manager(), 0);
    assertNull(ReflectionHelpers.getField(p, "sessionFailure"));
    assertEquals(0, ((CountDownLatch) ReflectionHelpers.getField(p, "sessionReady")).getCount());
    assertFalse((Boolean) ReflectionHelpers.getField(p, "verifiedSession"));
    assertEquals(
        "UNKNOWN", SessionState.evidence(SessionState.prefs(RuntimeEnvironment.getApplication())));
  }

  @Test
  public void rejectedCallbackAndPlatformFailureHaveDifferentCodes() throws Exception {
    for (boolean broken : new boolean[] {false, true}) {
      Manager m = new Manager();
      m.broken = broken;
      m.accepted = false;
      WebViewProfileProvider p = provider();
      p.applySessionCookies(m, 0);
      ProfileProvider.Failure e = ReflectionHelpers.getField(p, "sessionFailure");
      assertEquals(broken ? "WEBVIEW_UNAVAILABLE" : "COOKIE_WEBVIEW_REJECTED", e.code);
      assertFalse(SessionState.invalid(e));
      assertEquals(0, ((CountDownLatch) ReflectionHelpers.getField(p, "sessionReady")).getCount());
    }
  }
}
