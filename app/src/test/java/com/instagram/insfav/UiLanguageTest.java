package com.instagram.insfav;
import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class UiLanguageTest {
 @Test public void languagePersistsAndNativeCatalogMatchesChoice()throws Exception{
  android.content.Context c=RuntimeEnvironment.getApplication();UiLanguage.save(c,"tr");
  assertEquals("tr",UiLanguage.choice(c));assertEquals("Ayarlar",UiText.text(c,"Settings"));
  assertEquals("istanbul",Store.normalize("ISTANBUL"));
  UiLanguage.save(c,"en");assertEquals("Settings",UiText.text(c,"Settings"));
 }
 @Test public void unsupportedLanguageDoesNotReplaceSavedChoice()throws Exception{
  android.content.Context c=RuntimeEnvironment.getApplication();UiLanguage.save(c,"en");
  assertThrows(java.io.IOException.class,()->UiLanguage.save(c,"invalid"));assertEquals("en",UiLanguage.choice(c));
 }
}
