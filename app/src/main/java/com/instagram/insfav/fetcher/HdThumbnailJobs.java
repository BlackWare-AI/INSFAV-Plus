package com.instagram.insfav.fetcher;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Private, bounded local jobs. Never owns a WebView or participates in the network download queue. */
final class HdThumbnailJobs {
    private static final Set<String> active=ConcurrentHashMap.newKeySet();
    private static final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"insfav-hd-thumbnail");t.setDaemon(true);return t;});
    private static File directory(Context c){return new File(c.getCacheDir(),"hd-thumbnail-jobs");}
    private static String scope(Fetcher.Host h,String user){return h.tree()+"\n"+h.directory()+"\n"+user;}
    static boolean busy(Fetcher.Host h,String user){return active.contains(scope(h,user));}
    static void enqueue(Context c,Fetcher.Host h,String user,byte[] bytes)throws Exception {
        if(!Identity.valid(user))throw new IOException("INVALID_PROFILE");
        String key=scope(h,user);if(!active.add(key))return;
        File image=null,record=null;
        try {
            File dir=directory(c);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("CACHE_UNAVAILABLE");
            synchronized(HdThumbnailJobs.class) {
                File[] files=dir.listFiles();long size=0;int count=0;
                if(files!=null)for(File f:files){size+=f.length();if(f.getName().endsWith(".json"))count++;}
                if(count>=32||size+bytes.length>64L*1024*1024)throw new IOException("CACHE_FULL");
                String id=UUID.randomUUID().toString();image=new File(dir,id+".image");record=new File(dir,id+".json");
                try(FileOutputStream out=new FileOutputStream(image)){out.write(bytes);out.getFD().sync();}
                AtomicFile journal=new AtomicFile(record);FileOutputStream out=journal.startWrite();
                try{out.write(new JSONObject().put("user",user).put("tree",h.tree()).put("directory",h.directory()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));journal.finishWrite(out);}catch(Exception e){journal.failWrite(out);throw e;}
            }
            dispatch(c,h,user,key,image,record);
        }catch(Exception|OutOfMemoryError e){if(image!=null)image.delete();if(record!=null)record.delete();active.remove(key);throw e;}
    }
    private static void progress(String user,boolean busy){Fetcher.thumbnailProgress(user,busy);}
    private static void dispatch(Context c,Fetcher.Host h,String user,String key,File image,File record){
        progress(user,true);
        worker.execute(()->{
            try {
                // A different output-folder selection must not redirect an older job.
                if(!key.equals(scope(Fetcher.host(),user)))return;
                byte[] source=Files.readAllBytes(image.toPath());
                if(HdThumbnailWriter.save(c,h,user,source))Fetcher.publish(true);
            }catch(Exception|OutOfMemoryError ignored){new Diagnostics(c).log("IMAGE","THUMBNAIL_WRITE_FAILED",0);}
            finally{image.delete();record.delete();active.remove(key);progress(user,false);}
        });
    }
    static void recover(Context c,Fetcher.Host h){
        // Listing is local only; all reading/encoding/SAF work runs off the UI thread.
        worker.execute(()->{
            File dir=directory(c);File[] records=dir.listFiles((d,n)->n.endsWith(".json"));if(records==null)return;
            for(File record:records){File image=new File(dir,record.getName().replace(".json",".image"));
                try{
                    if(record.length()>8192||!image.isFile()||image.length()>8*1024*1024||System.currentTimeMillis()-record.lastModified()>7L*86400000)throw new IOException();
                    JSONObject meta=new JSONObject(new String(Files.readAllBytes(record.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                    String user=meta.getString("user"),key=scope(h,user);
                    if(!Identity.valid(user)||!h.tree().equals(meta.getString("tree"))||!h.directory().equals(meta.getString("directory")))continue;
                    if(active.add(key))dispatch(c,h,user,key,image,record);
                }catch(Exception e){record.delete();image.delete();}
            }
            // A crash before committing metadata may leave only a source file.
            File[] images=dir.listFiles((d,n)->n.endsWith(".image"));if(images!=null)for(File image:images)if(!new File(dir,image.getName().replace(".image",".json")).exists()&&System.currentTimeMillis()-image.lastModified()>86400000)image.delete();
        });
    }
    private HdThumbnailJobs(){}
}
