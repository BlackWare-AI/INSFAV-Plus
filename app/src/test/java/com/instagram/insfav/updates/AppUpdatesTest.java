package com.instagram.insfav.updates;

import static org.junit.Assert.*;
import android.content.*;
import android.content.pm.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class AppUpdatesTest {
    Context c;ShadowPackageManager pm;PackageInfo installed;
    JSONObject release(String repo,String tag)throws Exception{return new JSONObject().put("id",42).put("tag_name",tag).put("name","Changes").put("body","Fixes\n<script>no execution</script>").put("html_url","https://github.com/"+repo+"/releases/tag/"+tag).put("assets",new JSONArray().put(new JSONObject().put("name","app.apk").put("size",3).put("browser_download_url","https://github.com/"+repo+"/releases/download/"+tag+"/app.apk")));}
    @Before public void setup()throws Exception{
        Context base=RuntimeEnvironment.getApplication();c=new ContextWrapper(base){@Override public String getPackageName(){return com.instagram.insfav.BuildConfig.APPLICATION_ID;}};AppUpdates.prefs(c).edit().clear().commit();AppUpdates.apk(c).delete();
        installed=new PackageInfo();installed.packageName=c.getPackageName();installed.versionCode=19;installed.versionName="5.0.0";installed.signatures=new Signature[]{new Signature("010203")};installed.applicationInfo=new ApplicationInfo();installed.applicationInfo.packageName=installed.packageName;
        installed.signingInfo=new SigningInfo();Shadows.shadowOf(installed.signingInfo).setSignatures(installed.signatures);pm=Shadows.shadowOf(c.getPackageManager());pm.installPackage(installed);
    }
    HttpURLConnection response(URI uri,int status,byte[] body){return new HttpURLConnection(toUrl(uri)) {public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}public int getResponseCode(){return status;}public InputStream getInputStream(){return new ByteArrayInputStream(body);}};}
    static URL toUrl(URI uri){try{return uri.toURL();}catch(Exception e){throw new RuntimeException(e);}}
    @Test public void eachApplicationOnlyAcceptsItsOwnStableSignedAssetCandidate()throws Exception{
        for(String app:new String[]{"com.instagram.insfav","com.instagram.insfav.plus"}){String repo=ReleaseInfo.repo(app);JSONObject valid=release(repo,"v5.0.0-build.20");assertEquals(repo,ReleaseInfo.repo(app));assertEquals(42,ReleaseInfo.parse(app,valid).id);
            JSONObject draft=new JSONObject(valid.toString()).put("draft",true);assertThrows(Exception.class,()->ReleaseInfo.parse(app,draft));JSONObject pre=new JSONObject(valid.toString()).put("prerelease",true);assertThrows(Exception.class,()->ReleaseInfo.parse(app,pre));
            JSONObject cross=release(app.endsWith("plus")?"BlackWare-AI/INSFAV":"BlackWare-AI/INSFAV-Plus","v5.0.0-build.20");assertThrows(Exception.class,()->ReleaseInfo.parse(app,cross));
            JSONObject unsigned=new JSONObject(valid.toString());unsigned.getJSONArray("assets").getJSONObject(0).put("name","app-unsigned.apk");assertThrows(Exception.class,()->ReleaseInfo.parse(app,unsigned));
        }
        assertFalse(ReleaseInfo.trustedDownload(URI.create("http://github.com/x")));assertFalse(ReleaseInfo.trustedDownload(URI.create("https://github.com.evil.test/x")));assertFalse(ReleaseInfo.trustedDownload(URI.create("https://user@github.com/x")));assertFalse(ReleaseInfo.trustedDownload(URI.create("https://release-assets.githubusercontent.com:444/x")));
        assertTrue(ReleaseInfo.trustedDownload(URI.create("https://release-assets.githubusercontent.com/x")));
    }
    @Test public void backupGateRejectsOverlapAndAlwaysCanBeReleased(){
        assertFalse(UpdateBackup.busy());assertTrue(UpdateBackup.begin());try{assertTrue(UpdateBackup.busy());assertFalse(UpdateBackup.begin());}finally{UpdateBackup.end();}assertFalse(UpdateBackup.busy());assertTrue(UpdateBackup.begin());UpdateBackup.end();
    }
    @Test public void versionsUseApplicationNumberingAndNeverDowngrade(){assertTrue(ReleaseInfo.newer("plus-v1.1.15-build.19","1.1.15",18));assertTrue(ReleaseInfo.newer("v5.0.0-build.20-hd","5.0.0",19));assertFalse(ReleaseInfo.newer("v5.0.0-build.19","5.0.0",19));assertFalse(ReleaseInfo.newer("v4.9.9-build.99","5.0.0",19));assertFalse(ReleaseInfo.newer("arbitrary","5.0.0",19));assertTrue(ReleaseInfo.newer("v5.0.1","5.0.0",19));}
    @Test public void checkStoresUntrustedNotesAsDataAndKeepsHistoryWithoutDownloading()throws Exception{
        AtomicInteger requests=new AtomicInteger();JSONObject first=release(ReleaseInfo.repo(c.getPackageName()),"v5.0.0-build.20");AppUpdates.check(c,(uri,asset)->{assertFalse(asset);assertEquals("https://api.github.com/repos/"+ReleaseInfo.repo(c.getPackageName())+"/releases/latest",uri.toString());requests.incrementAndGet();return response(uri,200,first.toString().getBytes(StandardCharsets.UTF_8));});
        JSONObject state=AppUpdates.state(c);assertTrue(state.getBoolean("available"));assertFalse(state.getBoolean("ready"));assertFalse(state.getBoolean("download"));assertFalse(AppUpdates.apk(c).exists());assertTrue(state.getJSONObject("release").getString("notes").contains("<script>"));assertEquals(1,requests.get());
        JSONObject second=release(ReleaseInfo.repo(c.getPackageName()),"v5.0.0-build.21").put("id",43);AppUpdates.check(c,(uri,asset)->response(uri,200,second.toString().getBytes(StandardCharsets.UTF_8)));assertEquals(2,AppUpdates.state(c).getJSONArray("history").length());assertEquals(42,AppUpdates.state(c).getJSONArray("history").getJSONObject(1).getLong("id"));
    }
    @Test public void wrongPackageSignerOrVersionBlocksStagingAndTamperedFileBlocksInstallation()throws Exception{
        File target=AppUpdates.apk(c);assertTrue(target.getParentFile().isDirectory()||target.getParentFile().mkdirs());Files.write(target.toPath(),new byte[]{1,2,3});
        PackageInfo remote=new PackageInfo();remote.packageName=c.getPackageName();remote.versionCode=20;remote.signatures=installed.signatures;remote.signingInfo=installed.signingInfo;pm.setPackageArchiveInfo(target.getAbsolutePath(),remote);AppUpdates.verify(c,target);
        remote.packageName=c.getPackageName().endsWith(".plus")?"com.instagram.insfav":"com.instagram.insfav.plus";assertThrows(Exception.class,()->AppUpdates.verify(c,target));remote.packageName=c.getPackageName();remote.versionCode=19;assertThrows(Exception.class,()->AppUpdates.verify(c,target));remote.versionCode=20;remote.signatures=new Signature[]{new Signature("040506")};remote.signingInfo=new SigningInfo();Shadows.shadowOf(remote.signingInfo).setSignatures(remote.signatures);assertThrows(Exception.class,()->AppUpdates.verify(c,target));remote.signatures=installed.signatures;remote.signingInfo=installed.signingInfo;
        AppUpdates.prefs(c).edit().putBoolean("ready",true).putString("apkSha",AppUpdates.sha(target)).commit();AppUpdates.verifyReady(c);Files.write(target.toPath(),new byte[]{3,2,1});assertThrows(Exception.class,()->AppUpdates.verifyReady(c));
    }
    @Test public void truncatedOrRevokedAutomaticDownloadNeverBecomesReady()throws Exception{
        JSONObject release=ReleaseInfo.parse(c.getPackageName(),release(ReleaseInfo.repo(c.getPackageName()),"v5.0.0-build.20")).json();AppUpdates.prefs(c).edit().putBoolean("available",true).putString("release",release.toString()).commit();
        assertThrows(Exception.class,()->AppUpdates.download(c,false,(uri,asset)->response(uri,200,new byte[]{1,2})));assertFalse(AppUpdates.prefs(c).getBoolean("ready",false));assertFalse(AppUpdates.apk(c).exists());
        assertThrows(Exception.class,()->AppUpdates.download(c,true,(uri,asset)->response(uri,200,new byte[]{1,2,3})));assertFalse(AppUpdates.apk(c).exists());assertFalse(new File(AppUpdates.apk(c)+".part").exists());
    }
    @Test public void validDownloadIsReadBackAndNewReleaseInvalidatesStagedFile()throws Exception{
        JSONObject release=ReleaseInfo.parse(c.getPackageName(),release(ReleaseInfo.repo(c.getPackageName()),"v5.0.0-build.20")).json();AppUpdates.prefs(c).edit().putBoolean("available",true).putString("release",release.toString()).commit();File part=new File(AppUpdates.apk(c)+".part");PackageInfo remote=new PackageInfo();remote.packageName=c.getPackageName();remote.versionCode=20;remote.signatures=installed.signatures;remote.signingInfo=installed.signingInfo;pm.setPackageArchiveInfo(part.getAbsolutePath(),remote);
        AppUpdates.download(c,false,(uri,asset)->{assertTrue(asset);return response(uri,200,new byte[]{1,2,3});});assertTrue(AppUpdates.prefs(c).getBoolean("ready",false));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(AppUpdates.apk(c).toPath()));
        JSONObject newer=release(ReleaseInfo.repo(c.getPackageName()),"v5.0.0-build.21").put("id",43);AppUpdates.check(c,(uri,asset)->response(uri,200,newer.toString().getBytes(StandardCharsets.UTF_8)));assertFalse(AppUpdates.prefs(c).getBoolean("ready",true));assertFalse(AppUpdates.apk(c).exists());
    }
    @Test public void chunkedBackupRejectsMissingFinalFrameAndReorderedChunks()throws Exception{
        byte[] content=new byte[700000];new Random(7).nextBytes(content);ByteArrayOutputStream out=new ByteArrayOutputStream();UpdateBackup.encrypted(out,c.getPackageName(),"1234".toCharArray(),zip->{zip.putNextEntry(new java.util.zip.ZipEntry("synthetic.bin"));zip.write(content);zip.closeEntry();});byte[] bytes=out.toByteArray();UpdateBackup.verify(new ByteArrayInputStream(bytes),c.getPackageName(),"1234".toCharArray());
        byte[] truncated=Arrays.copyOf(bytes,bytes.length-20);assertThrows(Exception.class,()->UpdateBackup.verify(new ByteArrayInputStream(truncated),c.getPackageName(),"1234".toCharArray()));
        byte[] appended=Arrays.copyOf(bytes,bytes.length+1);assertThrows(Exception.class,()->UpdateBackup.verify(new ByteArrayInputStream(appended),c.getPackageName(),"1234".toCharArray()));
        byte[] reordered=bytes.clone();int offset=UpdateBackup.MAGIC.length+24,frame=4+UpdateBackup.CHUNK+16;System.arraycopy(bytes,offset+frame,reordered,offset,frame);System.arraycopy(bytes,offset,reordered,offset+frame,frame);assertThrows(Exception.class,()->UpdateBackup.verify(new ByteArrayInputStream(reordered),c.getPackageName(),"1234".toCharArray()));
        File fixture=new File("build/verification/update-backup-fixture.ifbackup");assertTrue(fixture.getParentFile().isDirectory()||fixture.getParentFile().mkdirs());Files.write(fixture.toPath(),bytes);
    }
    @Test public void encryptedBackupAuthenticatesPinApplicationAndEveryByte()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();UpdateBackup.encrypted(out,c.getPackageName(),"1234".toCharArray(),zip->{zip.putNextEntry(new java.util.zip.ZipEntry("favorites.json"));zip.write("SYNTHETIC_FAVORITES_AND_COOKIE".getBytes(StandardCharsets.UTF_8));zip.closeEntry();});byte[] bytes=out.toByteArray();assertFalse(new String(bytes,StandardCharsets.ISO_8859_1).contains("SYNTHETIC_FAVORITES_AND_COOKIE"));UpdateBackup.verify(new ByteArrayInputStream(bytes),c.getPackageName(),"1234".toCharArray());
        assertThrows(Exception.class,()->UpdateBackup.verify(new ByteArrayInputStream(bytes),c.getPackageName(),"4321".toCharArray()));assertThrows(Exception.class,()->UpdateBackup.verify(new ByteArrayInputStream(bytes),c.getPackageName()+".other","1234".toCharArray()));bytes[bytes.length-1]^=1;assertThrows(Exception.class,()->UpdateBackup.verify(new ByteArrayInputStream(bytes),c.getPackageName(),"1234".toCharArray()));
    }
}
