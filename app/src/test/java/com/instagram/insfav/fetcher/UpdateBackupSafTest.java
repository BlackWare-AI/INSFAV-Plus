package com.instagram.insfav.fetcher;
import static org.junit.Assert.*;
import com.instagram.insfav.updates.UpdateBackup;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class UpdateBackupSafTest {
    FetcherStorageTest fixture;
    @Before public void setup()throws Exception{fixture=new FetcherStorageTest();fixture.setup();}
    Map<String,byte[]> decode(byte[] bytes)throws Exception{
        ByteArrayInputStream raw=new ByteArrayInputStream(bytes);raw.skip(UpdateBackup.MAGIC.length);byte[] salt=new byte[16],prefix=new byte[8];new DataInputStream(raw).readFully(salt);new DataInputStream(raw).readFully(prefix);byte[] key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec("1234".toCharArray(),salt,UpdateBackup.ITERATIONS,256)).getEncoded();DataInputStream in=new DataInputStream(raw);ByteArrayOutputStream clear=new ByteArrayOutputStream();int seq=0;
        while(true){int len=in.readInt();byte[] encoded=new byte[len+16];in.readFully(encoded);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,ByteBuffer.allocate(12).put(prefix).putInt(seq).array()));cipher.updateAAD(fixture.context.getPackageName().getBytes(java.nio.charset.StandardCharsets.UTF_8));cipher.updateAAD(ByteBuffer.allocate(5).putInt(seq++).put((byte)(len==0?1:0)).array());clear.write(cipher.doFinal(encoded));if(len==0)break;}
        Map<String,byte[]> result=new HashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(clear.toByteArray()))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=zip.read(b))!=-1)out.write(b,0,n);result.put(entry.getName(),out.toByteArray());}}return result;
    }
    @Test public void writesVerifiedArchiveContainingFavoritesPrefsLogsCookiesAndConsistentDatabase()throws Exception{
        File keep=new File(fixture.provider.root,"keep.txt");Files.write(keep.toPath(),new byte[]{7});
        Files.write(new File(fixture.context.getFilesDir(),"document.json").toPath(),"SYNTHETIC_FAVORITES".getBytes());Files.write(new File(fixture.context.getFilesDir(),"diagnostics.txt").toPath(),"SYNTHETIC_LOG".getBytes());fixture.context.getSharedPreferences("appearance",0).edit().putString("view","matrix").commit();
        try(SQLiteDatabase db=fixture.context.openOrCreateDatabase("backup-queue.db",0,null)){
            db.enableWriteAheadLogging();db.execSQL("CREATE TABLE queue (username TEXT)");db.execSQL("INSERT INTO queue VALUES ('synthetic')");
            Uri saved=UpdateBackup.save(fixture.context,fixture.tree,"1234".toCharArray(),new JSONObject().put("settings","SYNTHETIC_SETTINGS"),"sessionid=SYNTHETIC_WEBVIEW_COOKIE");assertNotNull(saved);String id=android.provider.DocumentsContract.getDocumentId(saved);File file=fixture.provider.file(id);assertTrue(file.getName().endsWith(".ifbackup"));byte[] archive=Files.readAllBytes(file.toPath());assertFalse(new String(archive,java.nio.charset.StandardCharsets.ISO_8859_1).contains("SYNTHETIC_WEBVIEW_COOKIE"));Map<String,byte[]> entries=decode(archive);
            assertEquals("SYNTHETIC_FAVORITES",new String(entries.get("files/document.json")));assertEquals("SYNTHETIC_LOG",new String(entries.get("files/diagnostics.txt")));assertTrue(new String(entries.get("shared_prefs/appearance.xml")).contains("matrix"));assertEquals("sessionid=SYNTHETIC_WEBVIEW_COOKIE",new String(entries.get("portable/instagram-webview-cookie-header.txt")));assertNotNull(entries.get("databases/backup-queue.db"));
            File restored=Files.createTempDirectory("update-backup-sqlite").toFile();for(Map.Entry<String,byte[]> entry:entries.entrySet())if(entry.getKey().startsWith("databases/"))Files.write(new File(restored,entry.getKey().substring(10)).toPath(),entry.getValue());
            try(SQLiteDatabase read=SQLiteDatabase.openDatabase(new File(restored,"backup-queue.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);android.database.Cursor cursor=read.rawQuery("SELECT username FROM queue",null)){assertTrue(cursor.moveToFirst());assertEquals("synthetic",cursor.getString(0));}
            assertArrayEquals(new byte[]{7},Files.readAllBytes(keep.toPath()));assertEquals(2,fixture.provider.root.listFiles().length);
        }
    }
    @Test public void providerRenameFailureDeletesPartialAndPreservesExistingData()throws Exception{
        File keep=new File(fixture.provider.root,"keep.txt");Files.write(keep.toPath(),new byte[]{7});fixture.provider.rejectRename=true;
        assertThrows(Exception.class,()->UpdateBackup.save(fixture.context,fixture.tree,"1234".toCharArray(),new JSONObject(),""));assertArrayEquals(new byte[]{7},Files.readAllBytes(keep.toPath()));assertEquals(1,fixture.provider.root.listFiles().length);
    }
}
