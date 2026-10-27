package com.instagram.insfav;
import static org.junit.Assert.*;
import android.content.Intent;
import android.net.Uri;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE,application=InsfavApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ImagePublicationTest {
 @Test public void validationRunsOutsideStoreLockAndAnIndexReplacementRejectsLatePublication() throws Exception {
  LocalImagesTest harness=new LocalImagesTest();harness.setup();Store store=ReflectionHelpers.getField(harness,"store");LocalImages images=ReflectionHelpers.getField(harness,"images");LocalImagesTest.FixtureProvider provider=ReflectionHelpers.getField(harness,"provider");
  String tree="content://insfav.test/tree/root";images.select(Uri.parse(tree),Intent.FLAG_GRANT_READ_URI_PERMISSION);images.scan((e,v)->{});String original=store.original();
  InsfavApplication app=(InsfavApplication)RuntimeEnvironment.getApplication();ReflectionHelpers.setField(app,"store",store);
  provider.mustNotHold=store;provider.beforeOpen=()->{try{store.replaceIndex(new JSONObject().put("treeUri",tree).put("profiles",new JSONObject()));}catch(Exception failure){throw new RuntimeException(failure);}};
  app.imageWritten("late",Uri.parse("content://insfav.test/document/root%2FExample.JPG"),tree);
  assertEquals(0,store.index.getJSONObject("profiles").length());assertEquals(original,store.original());assertEquals(0,provider.writes);
 }
}
