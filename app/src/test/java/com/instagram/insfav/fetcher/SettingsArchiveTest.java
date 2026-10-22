package com.instagram.insfav.fetcher;
import android.content.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class SettingsArchiveTest {
 Context c;
 @Before public void setup(){c=RuntimeEnvironment.getApplication();QueueControl.lock.set(false);SessionState.prefs(c).edit().clear().commit();new CookieVault(c).remove();}
 @Test public void exportChangeImportRestoresAllUserPreferencesWithoutFavorites()throws Exception {
  SharedPreferences p=SessionState.prefs(c);p.edit().putInt("minimum",6).putInt("maximum",25).putInt("startMinute",1320).putInt("endMinute",360).putInt("concurrentWebViews",5).putBoolean("background",true).putLong("retryAt",123456789L).commit();
  char[] password="portable test password".toCharArray();String backup=SettingsArchive.export(c,password,new JSONObject());assertFalse(backup.contains("favorite_page_pref"));
  p.edit().putInt("minimum",30).putInt("maximum",60).putInt("concurrentWebViews",1).putBoolean("background",false).commit();
  SettingsArchive.apply(c,SettingsArchive.inspect(c,backup,password));
  assertEquals(6,p.getInt("minimum",0));assertEquals(25,p.getInt("maximum",0));assertEquals(1320,p.getInt("startMinute",0));assertEquals(360,p.getInt("endMinute",0));assertEquals(5,p.getInt("concurrentWebViews",0));assertTrue(p.getBoolean("background",false));assertEquals(123456789L,p.getLong("retryAt",0));assertFalse(p.getBoolean("armed",true));assertTrue(p.getBoolean("userPaused",false));
 }
 @Test public void portableEncryptionAuthenticatesCookiesPasswordAppAndVersion()throws Exception {
  JSONObject secret=new JSONObject().put("cookies","synthetic-secret-cookie");char[] pass="portable test password".toCharArray();
  String raw=SettingsArchive.encrypt("app.one",secret,pass);assertFalse(raw.contains("synthetic-secret-cookie"));assertEquals(secret.toString(),SettingsArchive.decrypt("app.one",raw,pass).toString());
  for(String app:new String[]{"app.two","app.one"})try{SettingsArchive.decrypt(app,raw,"wrong password".toCharArray());fail();}catch(java.io.IOException expected){}
  JSONObject tampered=new JSONObject(raw);tampered.put("version",2);try{SettingsArchive.decrypt("app.one",tampered.toString(),pass);fail();}catch(java.io.IOException expected){}
  tampered=new JSONObject(raw);tampered.put("data",tampered.getString("data").substring(3));try{SettingsArchive.decrypt("app.one",tampered.toString(),pass);fail();}catch(java.io.IOException expected){}
 }
 @Test public void malformedPreferencesAndFavoritesPayloadCannotMutateSettings()throws Exception {
  SessionState.prefs(c).edit().putInt("minimum",7).commit();JSONObject invalid=new JSONObject().put("preferences",new JSONObject()).put("extra",new JSONObject()).put("cookies",JSONObject.NULL);
  try{SettingsArchive.apply(c,invalid);fail();}catch(Exception expected){}assertEquals(7,SessionState.prefs(c).getInt("minimum",0));
  invalid.put("favorite_page_pref","[]");try{SettingsArchive.apply(c,invalid);fail();}catch(Exception expected){}assertEquals(7,SessionState.prefs(c).getInt("minimum",0));
 }
 @Test public void cookiesRestoreWithAnIndependentDestinationEncryptionKey()throws Exception {
  javax.crypto.SecretKey keyOne=javax.crypto.KeyGenerator.getInstance("AES").generateKey(),keyTwo=javax.crypto.KeyGenerator.getInstance("AES").generateKey();
  CookieVault original=new CookieVault(c,()->keyOne);original.save(CookieJar.parse("sessionid=SYNTHETIC_BACKUP_SESSION; ds_user_id=123",System.currentTimeMillis()));
  char[] password="portable test password".toCharArray();String backup=SettingsArchive.export(c,password,new JSONObject(),original);assertFalse(backup.contains("SYNTHETIC_BACKUP_SESSION"));original.remove();
  CookieVault destination=new CookieVault(c,()->keyTwo);SettingsArchive.apply(c,SettingsArchive.inspect(c,backup,password),destination);
  assertEquals("SYNTHETIC_BACKUP_SESSION",destination.load().value("sessionid",System.currentTimeMillis()));destination.remove();
 }
 @Test public void activeQueueRejectsImportWithoutChangingConfiguration()throws Exception {
  char[] pass="portable test password".toCharArray();String raw=SettingsArchive.export(c,pass,new JSONObject());SessionState.prefs(c).edit().putInt("minimum",9).commit();
  QueueControl.lock.set(true);try{SettingsArchive.apply(c,SettingsArchive.inspect(c,raw,pass));fail();}catch(java.io.IOException expected){assertEquals(9,SessionState.prefs(c).getInt("minimum",0));assertTrue(QueueControl.lock.get());}finally{QueueControl.lock.set(false);}
 }

 @Test public void originalPackageImportIsExplicitAndAuthenticatesTheExactOldApplication()throws Exception {
  Context plus=new ContextWrapper(c){@Override public String getPackageName(){return "com.instagram.insfav.plus";}};
  char[] password="offline migration password".toCharArray();
  JSONObject data=SettingsArchive.inspect(c,SettingsArchive.export(c,password,new JSONObject()),password);
  String old=SettingsArchive.encrypt("com.instagram.insfav",data,password);
  assertThrows(java.io.IOException.class,()->SettingsArchive.inspect(plus,old,password));
  assertEquals(data.toString(),SettingsArchive.inspectOriginal(plus,old,password).toString());
  final String relabeled=new JSONObject(old).put("application","com.instagram.insfav.plus").toString();
  assertThrows(java.io.IOException.class,()->SettingsArchive.inspectOriginal(plus,relabeled,password));
  final String forged=new JSONObject(old).put("application","untrusted.application").toString();
  assertThrows(java.io.IOException.class,()->SettingsArchive.inspectOriginal(plus,forged,password));
  assertThrows(java.io.IOException.class,()->SettingsArchive.inspectOriginal(plus,old,"incorrect password".toCharArray()));
  String normal=SettingsArchive.export(plus,password,new JSONObject());assertEquals("com.instagram.insfav.plus",new JSONObject(normal).getString("application"));
  assertThrows(java.io.IOException.class,()->SettingsArchive.inspectOriginal(plus,normal,password));
 }
 @Test public void changingEnvelopeToTheExpectedOldApplicationStillFailsGcmAuthentication()throws Exception {
  Context plus=new ContextWrapper(c){@Override public String getPackageName(){return "com.instagram.insfav.plus";}};
  char[] password="offline migration password".toCharArray();JSONObject data=SettingsArchive.inspect(c,SettingsArchive.export(c,password,new JSONObject()),password);
  String raw=SettingsArchive.encrypt("another.package",data,password);
  String changed=new JSONObject(raw).put("application","com.instagram.insfav").toString();
  assertThrows(java.io.IOException.class,()->SettingsArchive.inspectOriginal(plus,changed,password));
 }
 @Test public void extraSettingsFailureRollsBackFetcherConfigurationAndReleasesTheQueueLock()throws Exception {
  char[] pass="offline migration password".toCharArray();JSONObject data=SettingsArchive.inspect(c,SettingsArchive.export(c,pass,new JSONObject()),pass);
  SessionState.prefs(c).edit().putInt("minimum",9).commit();boolean[] rolled={false};
  assertThrows(java.io.IOException.class,()->SettingsArchive.apply(c,data,new SettingsArchive.Extras(){public void apply()throws Exception{throw new java.io.IOException("synthetic");}public void rollback(){rolled[0]=true;}}));
  assertTrue(rolled[0]);assertEquals(9,SessionState.prefs(c).getInt("minimum",0));assertFalse(QueueControl.lock.get());
 }
}
