package com.instagram.insfav;

import static org.junit.Assert.*;

import android.webkit.WebView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28)
public class MainListLifecycleTest {
  @Test
  public void pausingActivityCancelsWebHoldBeforeStop() {
    MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
    WebView web = new WebView(activity);
    ReflectionHelpers.setField(activity, "web", web);
    activity.onPause();
    assertTrue(Shadows.shadowOf(web).getLastEvaluatedJavascript().contains("app-background"));
    web.destroy();
  }
}
