package com.instagram.insfav.updates;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import com.instagram.insfav.fetcher.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Encrypted logical/app-data snapshot, never a diagnostic export. No plaintext staging file. */
public final class UpdateBackup {
    private static final java.util.concurrent.atomic.AtomicBoolean ACTIVE=new java.util.concurrent.atomic.AtomicBoolean();
    public static boolean busy(){return ACTIVE.get();}
    static boolean begin(){return ACTIVE.compareAndSet(false,true);}
    static void end(){ACTIVE.set(false);}
    public static final byte[] MAGIC="INSFAV-BACKUP-2\n".getBytes(StandardCharsets.US_ASCII);
    public static final int ITERATIONS=210000, CHUNK=256*1024;
    public interface Content { void write(ZipOutputStream out)throws Exception; }
    private static Cipher frame(int mode,byte[] key,byte[] prefix,int sequence,boolean last,String app)throws Exception{
        if(sequence<0)throw new IOException("Backup too large");byte[] iv=java.nio.ByteBuffer.allocate(12).put(prefix).putInt(sequence).array();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(app.getBytes(StandardCharsets.UTF_8));cipher.updateAAD(java.nio.ByteBuffer.allocate(5).putInt(sequence).put((byte)(last?1:0)).array());return cipher;
    }
    private static final class EncryptedStream extends OutputStream {
        final DataOutputStream out;final byte[] key,prefix;final String app;final byte[] buffer=new byte[CHUNK];int used,sequence;boolean closed;
        EncryptedStream(OutputStream out,byte[] key,byte[] prefix,String app){this.out=new DataOutputStream(out);this.key=key;this.prefix=prefix;this.app=app;}
        private void flushFrame(boolean last)throws IOException{try{byte[] encoded=frame(Cipher.ENCRYPT_MODE,key,prefix,sequence++,last,app).doFinal(buffer,0,used);out.writeInt(used);out.write(encoded);Arrays.fill(buffer,0,used,(byte)0);used=0;}catch(Exception e){throw new IOException("Backup encryption failed",e);}}
        @Override public void write(int value)throws IOException{byte[] b={(byte)value};write(b,0,1);}
        @Override public void write(byte[] b,int offset,int length)throws IOException{if(closed)throw new IOException("Closed");while(length>0){int n=Math.min(length,CHUNK-used);System.arraycopy(b,offset,buffer,used,n);offset+=n;length-=n;used+=n;if(used==CHUNK)flushFrame(false);}}
        @Override public void close()throws IOException{if(closed)return;try{if(used>0)flushFrame(false);flushFrame(true);out.flush();}finally{closed=true;Arrays.fill(buffer,(byte)0);out.close();}}
    }
    public static void encrypted(OutputStream target,String app,char[] pin,Content content)throws Exception{
        if(!BackupPinUi.valid(pin))throw new IOException("Four-digit PIN required");
        byte[] salt=new byte[16],prefix=new byte[8];SecureRandom random=new SecureRandom();random.nextBytes(salt);random.nextBytes(prefix);
        PBEKeySpec spec=new PBEKeySpec(pin,salt,ITERATIONS,256);byte[] key=null;
        try{key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();target.write(MAGIC);target.write(salt);target.write(prefix);
            try(ZipOutputStream zip=new ZipOutputStream(new EncryptedStream(target,key,prefix,app))){content.write(zip);}
        }finally{spec.clearPassword();if(key!=null)Arrays.fill(key,(byte)0);}
    }
    public static Uri save(Context c,Uri tree,char[] pin,JSONObject logical,String webCookies)throws Exception{
        Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        String name=(c.getPackageName().endsWith(".plus")?"INSFAV-Plus":"INSFAV")+"-before-update-"+System.currentTimeMillis()+".ifbackup";
        Uri partial=DocumentsContract.createDocument(c.getContentResolver(),parent,"application/octet-stream",name+".partial");if(partial==null)throw new IOException("Backup file could not be created");
        boolean done=false;try{
            try(OutputStream out=c.getContentResolver().openOutputStream(partial,"wt")){if(out==null)throw new IOException("Backup folder unavailable");encrypted(out,c.getPackageName(),pin,zip->{
                JSONObject meta=new JSONObject().put("format","INSFAV_UPDATE_BACKUP").put("version",1).put("application",c.getPackageName()).put("created",System.currentTimeMillis()).put("externalThumbnailsIncluded",false).put("webViewSiteStorageIncluded",false);
                entry(zip,"manifest.json",meta.toString().getBytes(StandardCharsets.UTF_8));entry(zip,"portable/settings.json",logical.toString().getBytes(StandardCharsets.UTF_8));
                entry(zip,"portable/instagram-webview-cookie-header.txt",webCookies.getBytes(StandardCharsets.UTF_8));
                copy(zip,c.getFilesDir(),"files/",c.getFilesDir().getCanonicalPath());
                File prefs=new File(c.getApplicationInfo().dataDir,"shared_prefs");copy(zip,prefs,"shared_prefs/",prefs.getCanonicalPath());
                File databases=new File(c.getApplicationInfo().dataDir,"databases");File[] all=databases.listFiles();if(all!=null)for(File db:all){if(db.getName().endsWith("-wal")||db.getName().endsWith("-shm")||db.getName().endsWith("-journal"))continue;
                    try(SQLiteDatabase snapshot=SQLiteDatabase.openDatabase(db.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){
                        // Exclusive writer transaction makes DB + WAL a consistent pair; SHM is rebuilt on restore.
                        snapshot.beginTransaction();try{file(zip,"databases/"+db.getName(),db);File wal=new File(db.getPath()+"-wal");if(wal.isFile())file(zip,"databases/"+wal.getName(),wal);}finally{snapshot.endTransaction();}
                    }
                }
                File jobs=new File(c.getCacheDir(),"hd-thumbnail-jobs");copy(zip,jobs,"pending-hd-jobs/",jobs.getCanonicalPath());
            });}
            // Read back every encrypted byte before considering the pre-install backup complete.
            try(InputStream in=c.getContentResolver().openInputStream(partial)){if(in==null)throw new IOException("Backup verification failed");verify(in,c.getPackageName(),pin);}
            Uri finished=DocumentsContract.renameDocument(c.getContentResolver(),partial,name);if(finished==null)throw new IOException("Backup could not be finalized");done=true;return finished;
        }finally{if(!done)try{DocumentsContract.deleteDocument(c.getContentResolver(),partial);}catch(Exception ignored){}}
    }
    public static void verify(InputStream source,String app,char[] pin)throws Exception{
        DataInputStream in=new DataInputStream(source);byte[] magic=new byte[MAGIC.length],salt=new byte[16],prefix=new byte[8];in.readFully(magic);if(!Arrays.equals(magic,MAGIC))throw new IOException("Invalid backup");in.readFully(salt);in.readFully(prefix);
        PBEKeySpec spec=new PBEKeySpec(pin,salt,ITERATIONS,256);byte[] key=null;
        try{key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();int sequence=0;while(true){int length=in.readInt();if(length<0||length>CHUNK)throw new IOException("Invalid frame");byte[] encoded=new byte[length+16];in.readFully(encoded);byte[] plain=frame(Cipher.DECRYPT_MODE,key,prefix,sequence++,length==0,app).doFinal(encoded);Arrays.fill(plain,(byte)0);if(length==0){if(in.read()!=-1)throw new IOException("Trailing data");break;}}}finally{spec.clearPassword();if(key!=null)Arrays.fill(key,(byte)0);}
    }
    private static void copy(ZipOutputStream zip,File root,String prefix,String allowed)throws Exception{File[] list=root.listFiles();if(list==null)return;for(File f:list){if(!f.getCanonicalPath().startsWith(allowed+File.separator))throw new IOException("Unexpected backup path");if(f.isDirectory())copy(zip,f,prefix+f.getName()+"/",allowed);else file(zip,prefix+f.getName(),f);}}
    private static void file(ZipOutputStream zip,String name,File file)throws Exception{zip.putNextEntry(new ZipEntry(name));try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)zip.write(b,0,n);}zip.closeEntry();}
    private static void entry(ZipOutputStream zip,String name,byte[] bytes)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
    private UpdateBackup(){}
}
