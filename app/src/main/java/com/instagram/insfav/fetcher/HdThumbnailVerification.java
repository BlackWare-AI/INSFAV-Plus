package com.instagram.insfav.fetcher;

import android.content.Context;
import android.util.AtomicFile;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.database.Cursor;
import java.io.*;
import org.json.*;

/** Private verification receipts, separate from existing thumbnail indexes and cookie storage. */
final class HdThumbnailVerification {
    private static String scope(Fetcher.Host h,String user){return h.tree()+"\n"+h.directory()+"\n"+user;}
    private static AtomicFile receipt(Context c,Fetcher.Host h,String user)throws IOException {
        File dir=new File(c.getFilesDir(),"hd-thumbnail-verification");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("CACHE_UNAVAILABLE");
        return new AtomicFile(new File(dir,HdImageCache.digest(scope(h,user).getBytes(java.nio.charset.StandardCharsets.UTF_8))+".json"));
    }
    private static JSONObject candidate(Context c,Fetcher.Host h,String user,byte[] hd)throws Exception {
        AtomicFile file=receipt(c,h,user);if(file.getBaseFile().length()>16384)return null;
        JSONObject saved=new JSONObject(new String(file.readFully(),java.nio.charset.StandardCharsets.UTF_8));
        return scope(h,user).equals(saved.optString("scope"))&&HdImageCache.digest(hd).equals(saved.optString("hd"))?saved:null;
    }
    static boolean matches(Context c,Fetcher.Host h,String user,byte[] hd){
        try{
            JSONObject saved=candidate(c,h,user,hd);if(saved==null)return false;
            String filename=Identity.outputFilename(user);Uri uri=Uri.parse(saved.optString("uri")),tree=Uri.parse(h.tree());
            // Filesystem document IDs prove parent+name directly. Opaque provider IDs use the folder lookup.
            if("content".equals(uri.getScheme())&&uri.getAuthority()!=null&&uri.getAuthority().equals(tree.getAuthority())
                &&(!DocumentsContract.isTreeUri(uri)||DocumentsContract.getTreeDocumentId(uri).equals(DocumentsContract.getTreeDocumentId(tree)))
                &&DocumentsContract.getDocumentId(uri).equals(h.directory()+"/"+filename)) {
                try(Cursor rows=c.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null)){
                    if(rows==null||!rows.moveToFirst()||!filename.equals(rows.getString(rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))))return false;
                }
                byte[] bytes=OutputFolder.read(c.getContentResolver().openInputStream(uri));
                return ThumbnailCodec.valid(bytes)&&HdImageCache.digest(bytes).equals(saved.optString("thumbnail"));
            }
            String actual=new OutputFolder(c,h.tree()).verifiedDigest(filename);return actual!=null&&actual.equals(saved.optString("thumbnail"));
        }catch(Exception e){return false;}
    }
    static boolean matches(Context c,Fetcher.Host h,String user,byte[] hd,OutputFolder folder){
        try{
            JSONObject saved=candidate(c,h,user,hd);if(saved==null)return false;
            String actual=folder.verifiedDigest(Identity.outputFilename(user));return actual!=null&&actual.equals(saved.optString("thumbnail"));
        }catch(Exception e){return false;}
    }
    static void record(Context c,Fetcher.Host h,String user,byte[] hd,OutputFolder folder){
        try{
            String filename=Identity.outputFilename(user),thumbnail=folder.verifiedDigest(filename);if(thumbnail==null)return;
            byte[] bytes=new JSONObject().put("scope",scope(h,user)).put("hd",HdImageCache.digest(hd)).put("thumbnail",thumbnail).put("uri",folder.uri(filename).toString()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            AtomicFile atomic=receipt(c,h,user);FileOutputStream out=atomic.startWrite();try{out.write(bytes);atomic.finishWrite(out);}catch(IOException e){atomic.failWrite(out);}
        }catch(Exception ignored){}
    }
    private HdThumbnailVerification(){}
}
