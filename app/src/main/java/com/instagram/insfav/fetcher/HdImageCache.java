package com.instagram.insfav.fetcher;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Private HD cache; a fresh identity-bound profile result is required before reuse. No cookies. */
final class HdImageCache {
    private static final Object LOCK=new Object();
    private static final Set<String> SIGNING=new HashSet<>(Arrays.asList("oh","oe","ccb","_nc_ohc","_nc_ht","_nc_cat","_nc_sid","_nc_gid","_nc_oc","_nc_ad","_nc_zt"));
    static String digest(byte[] bytes) {
        try {byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static JSONObject identity(URL url)throws Exception {
        if(url.toString().length()>8192)throw new IOException("INVALID_SOURCE");
        String host=url.getHost().toLowerCase(Locale.ROOT),path=url.getPath();
        boolean immutable=(host.endsWith(".cdninstagram.com")||host.endsWith(".fbcdn.net"))&&path.matches(".*/[0-9]+(?:_[0-9]+){1,4}_n\\.(?:jpg|jpeg|png|webp)");
        ArrayList<String> params=new ArrayList<>();
        if(url.getQuery()!=null)for(String pair:url.getQuery().split("&")){String[] parts=pair.split("=",2);String key=URLDecoder.decode(parts[0],"UTF-8"),value=parts.length==2?URLDecoder.decode(parts[1],"UTF-8"):"";if(!SIGNING.contains(key))params.add(key+"\u0000"+value);}
        Collections.sort(params);JSONArray query=new JSONArray();for(String pair:params){int split=pair.indexOf('\0');query.put(new JSONArray().put(pair.substring(0,split)).put(pair.substring(split+1)));}
        return new JSONObject().put("immutable",immutable).put("path",path).put("query",query);
    }
    static boolean sameObject(JSONObject stored,URL fresh)throws Exception {
        JSONObject now=identity(fresh);return stored.optBoolean("immutable")&&now.optBoolean("immutable")&&stored.optString("path").equals(now.getString("path"))&&stored.getJSONArray("query").toString().equals(now.getJSONArray("query").toString());
    }
    static final class Entry {final JSONObject meta;final byte[] bytes;Entry(JSONObject m,byte[] b){meta=m;bytes=b;}}
    private static File directory(Context c){return new File(c.getCacheDir(),"hd-viewer-cache");}
    private static String key(String user){return digest(user.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static Entry load(Context c,String user) {
        synchronized(LOCK){try {
            File dir=directory(c),record=new File(dir,key(user)+".json"),image=new File(dir,key(user)+".image");
            if(!record.isFile()||record.length()>16384||!image.isFile()||image.length()==0||image.length()>8*1024*1024)return null;
            JSONObject meta=new JSONObject(new String(new AtomicFile(record).readFully(),java.nio.charset.StandardCharsets.UTF_8));
            if(!user.equals(meta.optString("user")))return null;
            URL source=new URL(meta.getString("url"));if(!PublicProfileProvider.trusted(source,true))return null;
            JSONObject expected=identity(source);if(expected.getBoolean("immutable")!=meta.optBoolean("immutable")||!expected.getString("path").equals(meta.optString("path"))||!expected.getJSONArray("query").toString().equals(meta.getJSONArray("query").toString()))return null;
            byte[] bytes=Files.readAllBytes(image.toPath());if(!digest(bytes).equals(meta.optString("sha"))||!ThumbnailCodec.completeContainer(bytes))return null;
            image.setLastModified(System.currentTimeMillis());return new Entry(meta,bytes);
        }catch(Exception ignored){return null;}}
    }
    static android.webkit.WebResourceResponse resource(Entry cached,String user,String raw) {
        if(cached==null||!user.equals(cached.meta.optString("user")))return null;
        try {
            URL url=new URL(raw);if(!PublicProfileProvider.trusted(url,true)||!sameObject(cached.meta,url))return null;
            String mime=cached.meta.optString("mime");if(!Arrays.asList("image/jpeg","image/png","image/webp").contains(mime))return null;
            Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("Access-Control-Allow-Origin","*");
            return new android.webkit.WebResourceResponse(mime,null,200,"OK",headers,new ByteArrayInputStream(cached.bytes));
        }catch(Exception e){return null;}
    }
    static byte[] fetch(Context c,String user,URL url,PublicProfileProvider transfer)throws ProfileProvider.Failure {
        if(!PublicProfileProvider.trusted(url,true)||PublicProfileProvider.placeholder(url.toString()))throw new ProfileProvider.Failure("UNTRUSTED_SOURCE");
        Entry cached=load(c,user);
        if(cached!=null)try{if(sameObject(cached.meta,url))return cached.bytes;}catch(Exception ignored){}
        byte[] bytes;
        if(cached!=null&&url.toString().equals(cached.meta.optString("url"))&&strongEtag(cached.meta.optString("etag")))
            bytes=transfer.downloadConditional(url,cached.meta.optString("etag"),cached.bytes);
        else bytes=transfer.download(url);
        if(cached!=null&&bytes==cached.bytes)return bytes; // Validated304: no disk rewrite or decoder pass.
        try{store(c,user,url,bytes,transfer.imageEtag);}catch(Exception ignored){} // A cache failure must not hide a valid HD response.
        return bytes;
    }
    static boolean strongEtag(String tag){return tag!=null&&tag.matches("\"[!#-~]{1,256}\"");}
    static void store(Context c,String user,URL url,byte[] bytes,String etag)throws Exception {
        if(!Identity.valid(user)||!PublicProfileProvider.trusted(url,true)||PublicProfileProvider.placeholder(url.toString()))throw new IOException("UNTRUSTED_SOURCE");
        android.graphics.BitmapFactory.Options bounds=HdThumbnailWriter.validate(bytes);
        JSONObject meta=identity(url).put("user",user).put("url",url.toString()).put("sha",digest(bytes)).put("width",bounds.outWidth).put("height",bounds.outHeight).put("mime",bounds.outMimeType).put("etag",strongEtag(etag)?etag:"");
        synchronized(LOCK){File dir=directory(c);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("CACHE_UNAVAILABLE");
            File image=new File(dir,key(user)+".image"),record=new File(dir,key(user)+".json");
            write(image,bytes);write(record,meta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            File[] images=dir.listFiles((d,n)->n.endsWith(".image"));if(images==null)return;
            Arrays.sort(images,Comparator.comparingLong(File::lastModified));long size=0;for(File f:images)size+=f.length();int count=images.length;
            for(File f:images){if(size<=64L*1024*1024&&count<=64)break;if(f.equals(image))continue;size-=f.length();count--;new File(dir,f.getName().replace(".image",".json")).delete();f.delete();}
        }
    }
    private static void write(File file,byte[] bytes)throws IOException {AtomicFile atomic=new AtomicFile(file);FileOutputStream out=atomic.startWrite();try{out.write(bytes);atomic.finishWrite(out);}catch(IOException e){atomic.failWrite(out);throw e;}}
    private HdImageCache(){}
}
