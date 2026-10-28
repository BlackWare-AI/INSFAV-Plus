package com.instagram.insfav.updates;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import androidx.work.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Independent opt-in updater. Never starts or resumes thumbnail retrieval. */
@android.annotation.SuppressLint("ApplySharedPref")
public final class AppUpdates {
    public static final String JOB="insfav-release-check",CHANNEL="application-updates";
    private static final int NOTICE=7301;
    public static SharedPreferences prefs(Context c){return c.getSharedPreferences("application-updates",0);}
    public static File apk(Context c){return new File(c.getCacheDir(),"application-updates/update.apk");}
    public static void schedule(Context c){
        if(!prefs(c).getBoolean("check",false)){WorkManager.getInstance(c).cancelUniqueWork(JOB);return;}
        Constraints network=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(JOB,ExistingPeriodicWorkPolicy.KEEP,new PeriodicWorkRequest.Builder(UpdateWorker.class,12,TimeUnit.HOURS).setConstraints(network).build());
    }
    public static void options(Context c,JSONObject in)throws Exception{
        SharedPreferences p=prefs(c);boolean check=in.optBoolean("check"),download=in.optBoolean("download"),install=in.optBoolean("install");
        if(install){download=true;check=true;}if(download)check=true;
        if(!p.edit().putBoolean("check",check).putBoolean("download",download).putBoolean("install",install).putBoolean("backup",in.optBoolean("backup")).commit())throw new IOException("Preferences could not be saved");schedule(c);
    }
    public static JSONObject state(Context c)throws Exception{
        SharedPreferences p=prefs(c);String stored=p.getString("release","");if(!stored.isEmpty()&&p.getBoolean("available",false)){PackageInfo installed=c.getPackageManager().getPackageInfo(c.getPackageName(),0);if(!ReleaseInfo.newer(new JSONObject(stored).getString("tag"),installed.versionName,code(installed))){p.edit().putBoolean("available",false).putBoolean("ready",false).putString("status","current").commit();apk(c).delete();}}
        JSONObject out=new JSONObject().put("repo",ReleaseInfo.repo(c.getPackageName()));
        for(String k:new String[]{"check","download","install","backup"})out.put(k,p.getBoolean(k,false));
        out.put("folder",p.getString("folderLabel","")).put("lastCheck",p.getLong("lastCheck",0)).put("status",p.getString("status","")).put("ready",p.getBoolean("ready",false)&&apk(c).isFile()).put("available",p.getBoolean("available",false)).put("reviewed",p.getLong("reviewed",0));
        out.put("history",new JSONArray(p.getString("history","[]")));
        String release=p.getString("release","");if(!release.isEmpty())out.put("release",new JSONObject(release));
        return out;
    }
    public static void status(Context c,String value){prefs(c).edit().putString("status",value).commit();}
    interface Connections { HttpURLConnection open(URI uri,boolean asset)throws Exception; }
    public static void check(Context c)throws Exception{check(c,AppUpdates::open);}
    static synchronized void check(Context c,Connections network)throws Exception{
        SharedPreferences p=prefs(c);status(c,"checking");
        try{
            HttpURLConnection connection=network.open(URI.create("https://api.github.com/repos/"+ReleaseInfo.repo(c.getPackageName())+"/releases/latest"),false);
            String raw;try{if(connection.getResponseCode()!=200)throw new IOException("Release unavailable");try(InputStream in=connection.getInputStream()){raw=new String(read(in,2*1024*1024),StandardCharsets.UTF_8);}}finally{connection.disconnect();}
            ReleaseInfo release=ReleaseInfo.parse(c.getPackageName(),new JSONObject(raw));PackageInfo installed=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
            boolean available=ReleaseInfo.newer(release.tag,installed.versionName,code(installed));String previous=p.getString("release","");JSONObject value=release.json();
            JSONArray history=new JSONArray();history.put(value);JSONArray oldHistory=new JSONArray(p.getString("history","[]"));for(int n=0;n<oldHistory.length()&&history.length()<50;n++){JSONObject old=oldHistory.getJSONObject(n);if(old.optLong("id")!=release.id)history.put(old);}
            boolean same=!previous.isEmpty()&&new JSONObject(previous).optLong("id")==release.id&&new JSONObject(previous).optString("asset").equals(release.asset)&&new JSONObject(previous).optString("digest").equals(release.digest)&&new JSONObject(previous).optLong("size")==release.size;
            SharedPreferences.Editor edit=p.edit().putString("release",value.toString()).putString("history",history.toString()).putBoolean("available",available).putLong("lastCheck",System.currentTimeMillis()).putString("status",available?"available":"current");
            if(!same||!available){edit.putBoolean("ready",false).remove("apkSha");apk(c).delete();}
            if(!edit.commit())throw new IOException("Release could not be saved");
            if(available&&p.getLong("notified",0)!=release.id){if(notice(c,release.title))p.edit().putLong("notified",release.id).commit();}
        }catch(Exception e){status(c,"check-failed");throw e;}
    }
    public static void download(Context c)throws Exception{download(c,false);}
    public static void download(Context c,boolean automatic)throws Exception{download(c,automatic,AppUpdates::open);}
    static synchronized void download(Context c,boolean automatic,Connections network)throws Exception{
        SharedPreferences p=prefs(c);if(!p.getBoolean("available",false))throw new IOException("No newer release");JSONObject release=new JSONObject(p.getString("release",""));
        File target=apk(c),part=new File(target.getPath()+".part");File dir=target.getParentFile();if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Storage unavailable");
        status(c,"downloading");p.edit().putBoolean("ready",false).commit();
        try{
            URI source=URI.create(release.getString("asset"));String prefix="https://github.com/"+ReleaseInfo.repo(c.getPackageName())+"/releases/download/";if(!source.toString().startsWith(prefix))throw new IOException("Wrong repository");
            HttpURLConnection connection=network.open(source,true);try{if(connection.getResponseCode()!=200)throw new IOException("Download failed");long total=0;try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(part)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1){if(automatic&&!p.getBoolean("download",false))throw new IOException("Automatic downloads disabled");if(Thread.currentThread().isInterrupted())throw new IOException("Cancelled");total+=n;if(total>release.getLong("size")||total>256L*1024*1024)throw new IOException("Unexpected APK size");out.write(bytes,0,n);}out.getFD().sync();}if(total!=release.getLong("size"))throw new IOException("Incomplete APK");}finally{connection.disconnect();}
            String hash=sha(part),expected=release.optString("digest","");if(!expected.isEmpty()&&!expected.equalsIgnoreCase("sha256:"+hash))throw new IOException("APK checksum mismatch");verify(c,part);
            Files.move(part.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if(!p.edit().putBoolean("ready",true).putString("apkSha",hash).putString("status","ready").commit())throw new IOException("Download state unavailable");notice(c,release.optString("title"));
        }catch(Exception e){part.delete();target.delete();p.edit().putBoolean("ready",false).putString("status","download-failed").commit();throw e;}
    }
    public static void verifyReady(Context c)throws Exception{
        SharedPreferences p=prefs(c);File file=apk(c);if(!p.getBoolean("ready",false)||!file.isFile()||!sha(file).equals(p.getString("apkSha","")))throw new IOException("APK missing or changed");verify(c,file);
    }
    static void verify(Context c,File file)throws Exception{
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo remote=c.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),flags),local=c.getPackageManager().getPackageInfo(c.getPackageName(),flags);
        if(remote==null||!c.getPackageName().equals(remote.packageName)||code(remote)<=code(local))throw new IOException("Wrong application or non-upgrade APK");
        if(!signatures(local).equals(signatures(remote)))throw new IOException("APK signer differs from installed application");
    }
    @SuppressWarnings("deprecation") private static Set<String> signatures(PackageInfo info)throws Exception{
        Signature[] list=Build.VERSION.SDK_INT>=28?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;
        if(list==null||list.length==0)throw new IOException("Unsigned APK");Set<String> values=new HashSet<>();for(Signature s:list)values.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));return values;
    }
    @SuppressWarnings("deprecation") public static long code(PackageInfo info){return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    static HttpURLConnection open(URI source,boolean asset)throws Exception{
        URI uri=source;for(int i=0;i<6;i++){
            if(asset?!ReleaseInfo.trustedDownload(uri):!uri.toString().startsWith("https://api.github.com/repos/"))throw new IOException("Untrusted release host");
            HttpURLConnection con=(HttpURLConnection)uri.toURL().openConnection();con.setInstanceFollowRedirects(false);con.setConnectTimeout(20000);con.setReadTimeout(60000);con.setUseCaches(false);con.setRequestProperty("Accept",asset?"application/octet-stream":"application/vnd.github+json");con.setRequestProperty("User-Agent","INSFAV-Release-Updater");
            int code;try{code=con.getResponseCode();}catch(Exception failure){con.disconnect();throw failure;}if(code==301||code==302||code==303||code==307||code==308){String next=con.getHeaderField("Location");con.disconnect();if(next==null)throw new IOException("Missing redirect");uri=uri.resolve(next);continue;}return con;
        }throw new IOException("Too many redirects");
    }
    static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>limit)throw new IOException("Response too large");out.write(bytes,0,n);}return out.toByteArray();}
    static String sha(File f)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}return hex(hash.digest());}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static boolean notice(Context c,String title){
        NotificationManager manager=c.getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Application updates",NotificationManager.IMPORTANCE_DEFAULT));
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        Intent intent=c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());if(intent==null)return false;intent.putExtra("update-review",true);
        PendingIntent tap=PendingIntent.getActivity(c,NOTICE,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        manager.notify(NOTICE,new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(title).setContentText(UpdateText.text(c,"Güncelleme var · Yüklemeden önce yenilikleri inceleyin")).setContentIntent(tap).setAutoCancel(true).build());return true;
    }
    private AppUpdates(){}
}
