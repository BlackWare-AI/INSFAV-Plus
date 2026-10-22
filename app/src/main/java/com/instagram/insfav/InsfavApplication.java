package com.instagram.insfav;
import android.app.Application;
import android.net.Uri;
import com.instagram.insfav.fetcher.Fetcher;
import org.json.*;
import java.util.*;
/** Connects the existing read-only catalog to a narrowly scoped, user-authorized writer. */
public final class InsfavApplication extends Application implements Fetcher.Host {
 private Store store;
 @Override public void onCreate(){super.onCreate();store=Store.get(this);Fetcher.initialize(this,this);}
 @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level==TRIM_MEMORY_RUNNING_LOW||level==TRIM_MEMORY_RUNNING_CRITICAL||level==TRIM_MEMORY_COMPLETE)Fetcher.memoryPressure();}
 @Override public void onLowMemory(){super.onLowMemory();Fetcher.memoryPressure();}
 public Collection<String> users()throws Exception{return new ArrayList<>(store.users());}
 public String tree(){synchronized(store){return store.settings.optString("treeUri");}}
 public String directory(){synchronized(store){return store.settings.optString("directoryId");}}
 public long revision(){synchronized(store){return store.index.optLong("revision");}}
 public JSONObject catalog()throws Exception{synchronized(store){return new JSONObject(store.index.toString());}}
 public boolean hasImage(String user){
  String uri;
  synchronized(store){JSONObject p=store.index.optJSONObject("profiles"),i=p==null?null:p.optJSONObject(user);if(!tree().equals(store.index.optString("treeUri"))||i==null||!i.optBoolean("readable"))return false;uri=i.optString("uri");}
  try{android.graphics.BitmapFactory.Options b=ImageCodec.bounds(getContentResolver(),Uri.parse(uri));return ImageCodec.validate(getContentResolver(),Uri.parse(uri),b);}catch(Exception|OutOfMemoryError error){return false;}
 }
 public void imageWritten(String user,Uri uri,String tree)throws Exception{
  synchronized(store){
   if(!tree.equals(tree()))return;
   android.graphics.BitmapFactory.Options bounds=ImageCodec.bounds(getContentResolver(),uri);
   if(!ImageCodec.validate(getContentResolver(),uri,bounds))throw new java.io.IOException("Written thumbnail validation failed");
   JSONObject profiles=store.index.optJSONObject("profiles");if(profiles==null){profiles=new JSONObject();store.index.put("profiles",profiles);}
   boolean added=!profiles.has(user);
   long size=-1,modified=System.currentTimeMillis();
   try(android.database.Cursor c=getContentResolver().query(uri,new String[]{android.provider.DocumentsContract.Document.COLUMN_SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))size=c.getLong(0);}
   JSONObject old=profiles.optJSONObject(user);boolean replacing=old!=null&&(user+".webp").equals(old.optString("name"));long oldSize=replacing?Math.max(0,old.optLong("size")):0;
   if(!added&&!replacing)store.index.put("duplicates",store.index.optInt("duplicates")+1);
   if(replacing&&!old.optBoolean("readable"))store.index.put("unreadable",Math.max(0,store.index.optInt("unreadable")-1));
   profiles.put(user,new JSONObject().put("uri",uri.toString()).put("name",user+".webp").put("readable",true).put("width",bounds.outWidth).put("height",bounds.outHeight).put("size",size).put("modified",modified));
   store.index.put("treeUri",tree).put("revision",Math.max(System.currentTimeMillis(),store.index.optLong("revision")+1)).put("bytes",Math.max(0,store.index.optLong("bytes")-oldSize+Math.max(0,size))).put("files",store.index.optInt("files")+(added||!replacing?1:0));
   store.replaceIndex(store.index);
  }
 }
}
