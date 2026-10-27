package com.instagram.insfav.updates;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.CookieManager;
import androidx.core.content.FileProvider;
import com.instagram.insfav.fetcher.BackupPinUi;
import org.json.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Activity-bound commands; work and downloads never block the viewer or the main thread. */
public final class UpdateUi {
    public interface Host { void send(String event,Object value); JSONObject backup(char[] pin)throws Exception; void idle()throws Exception; }
    private final Activity activity;private final Host host;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private static final int FOLDER=76;private boolean installAttempted;private AlertDialog backupProgress;
    public UpdateUi(Activity a,Host h){activity=a;host=h;AppUpdates.schedule(a);}
    public boolean command(String command,JSONObject input){if(!command.startsWith("updates-"))return false;
        switch(command){
            case "updates-status": state();maybeAutomatic();break;
            case "updates-check": io.execute(()->{try{AppUpdates.check(activity);if(AppUpdates.prefs(activity).getBoolean("download",false)&&AppUpdates.prefs(activity).getBoolean("available",false)&&!AppUpdates.prefs(activity).getBoolean("ready",false))AppUpdates.download(activity,true);}catch(Exception e){error("Güncelleme kontrolü/indirmesi tamamlanamadı. Tekrar deneyin.");}finally{state();maybeAutomatic();}});break;
            case "updates-options": try{AppUpdates.options(activity,input);if(input.optBoolean("check"))notificationPermission();}catch(Exception e){error("Güncelleme tercihleri kaydedilemedi.");}state();break;
            case "updates-folder": activity.runOnUiThread(()->activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),FOLDER));break;
            case "updates-install": io.execute(()->{
                try{JSONObject release=new JSONObject(AppUpdates.prefs(activity).getString("release",""));if(input.optLong("id")!=release.getLong("id")||!AppUpdates.prefs(activity).getBoolean("available",false))throw new IOException();
                    AppUpdates.prefs(activity).edit().putLong("reviewed",release.getLong("id")).commit();installAttempted=false;
                    if(!AppUpdates.prefs(activity).getBoolean("ready",false)){AppUpdates.download(activity);state();}activity.runOnUiThread(this::prepare);
                }catch(Exception e){error("Güncelleme indirilemedi veya APK doğrulaması başarısız. Paket ve imza mevcut kurulumla aynı olmalı.");state();}
            });break;
            case "updates-release-page": io.execute(()->{try{org.json.JSONArray history=new org.json.JSONArray(AppUpdates.prefs(activity).getString("history","[]"));for(int n=0;n<history.length();n++){JSONObject release=history.getJSONObject(n);if(release.optLong("id")==input.optLong("id")){String url=release.getString("url");if(!url.startsWith("https://github.com/"+ReleaseInfo.repo(activity.getPackageName())+"/releases/tag/"))throw new IOException();activity.runOnUiThread(()->activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))));return;}}}catch(Exception e){error("Release page unavailable");}});break;
            case "updates-repository": activity.runOnUiThread(()->{String repo=input.optBoolean("other")?(activity.getPackageName().endsWith(".plus")?"BlackWare-AI/INSFAV":"BlackWare-AI/INSFAV-Plus"):ReleaseInfo.repo(activity.getPackageName());activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/"+repo)));});break;
            default:error("Unknown update command");
        }return true;
    }
    private void maybeAutomatic(){
        try{android.content.SharedPreferences p=AppUpdates.prefs(activity);if(p.getBoolean("install",false)&&p.getBoolean("ready",false)&&p.getBoolean("available",false)&&p.getLong("reviewed",0)==new JSONObject(p.getString("release","")).getLong("id"))activity.runOnUiThread(this::prepare);}catch(Exception ignored){}
    }
    private void prepare(){
        if(activity.isFinishing()||activity.isDestroyed()||!activity.hasWindowFocus()||installAttempted)return;installAttempted=true;
        if(!activity.getPackageManager().canRequestPackageInstalls()){
            new AlertDialog.Builder(activity,android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(UpdateText.text(activity,"Güncelleme yükleme izni")).setMessage(UpdateText.text(activity,"Android ayarlarında bu uygulamanın güncelleme yüklemesine izin verin. Ardından güncelleme butonuna yeniden dokunun.")).setPositiveButton(UpdateText.text(activity,"Ayarları aç"),(d,w)->activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())))).setNegativeButton(UpdateText.text(activity,"Vazgeç"),null).show();return;
        }
        if(AppUpdates.prefs(activity).getBoolean("backup",false)){
            if(AppUpdates.prefs(activity).getString("folder","").isEmpty()){error("Önce güncelleme yedeği klasörünü seçin. Kurulum başlatılmadı.");return;}
            BackupPinUi.show(activity,UpdateText.text(activity,"Güncelleme öncesi tam veri yedeği"),true,pin->{
                CookieManager manager=CookieManager.getInstance();manager.flush();String cookies=manager.getCookie("https://www.instagram.com/");final String header=cookies==null?"":cookies;
                io.execute(()->{if(!UpdateBackup.begin()){Arrays.fill(pin,'\0');error("Yedekleme zaten sürüyor.");return;}activity.runOnUiThread(()->{
                    if(activity.isFinishing()||activity.isDestroyed())return;android.widget.LinearLayout content=new android.widget.LinearLayout(activity);content.setPadding(36,32,36,32);content.setBackgroundColor(android.graphics.Color.BLACK);content.addView(new android.widget.ProgressBar(activity));android.widget.TextView text=new android.widget.TextView(activity);text.setText(UpdateText.text(activity,"Yedek kaydediliyor ve doğrulanıyor…"));text.setTextColor(android.graphics.Color.WHITE);text.setPadding(24,0,0,0);content.addView(text,new android.widget.LinearLayout.LayoutParams(0,-2,1));backupProgress=new AlertDialog.Builder(activity,AlertDialog.THEME_DEVICE_DEFAULT_DARK).setView(content).setCancelable(false).create();backupProgress.show();backupProgress.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
                });try{host.idle();AppUpdates.verifyReady(activity);AppUpdates.status(activity,"backing-up");state();
                    JSONObject logical=host.backup(pin);Uri saved=UpdateBackup.save(activity,Uri.parse(AppUpdates.prefs(activity).getString("folder","")),pin,logical,header);
                    AppUpdates.prefs(activity).edit().putString("lastBackup",saved.toString()).commit();install();
                }catch(Exception e){AppUpdates.status(activity,"backup-failed");error("Yedek tamamlanamadı. İndirmeyi/taramayı duraklatın, HD dönüşümünün bitmesini bekleyin ve klasör erişimini kontrol edin. Kurulum başlatılmadı.");state();}finally{UpdateBackup.end();Arrays.fill(pin,'\0');activity.runOnUiThread(()->{if(backupProgress!=null){backupProgress.dismiss();backupProgress=null;}});}});
            });
        }else io.execute(()->{try{host.idle();install();}catch(Exception e){error("Kurulum başlatılamadı. İşlemleri duraklatın; paket, imza ve dosyayı kontrol edin.");}});
    }
    private void install()throws Exception{
        final java.io.File installFile;
        synchronized(AppUpdates.class){
            JSONObject release=new JSONObject(AppUpdates.prefs(activity).getString("release",""));long id=release.getLong("id");if(AppUpdates.prefs(activity).getLong("reviewed",0)!=id)throw new IOException("Release changed; review again");
            AppUpdates.verifyReady(activity);installFile=new java.io.File(AppUpdates.apk(activity).getParentFile(),"approved-"+id+"-"+AppUpdates.prefs(activity).getString("apkSha","").substring(0,16)+".apk");
            java.nio.file.Files.copy(AppUpdates.apk(activity).toPath(),installFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if(!AppUpdates.sha(installFile).equals(AppUpdates.prefs(activity).getString("apkSha","")))throw new IOException("APK copy changed");
        }
        activity.runOnUiThread(()->{if(activity.isFinishing()||activity.isDestroyed()||!activity.hasWindowFocus()){error("Yedek hazır. Kurulum için uygulamaya dönüp güncelleme butonuna dokunun.");return;}try{
            Uri apk=FileProvider.getUriForFile(activity,activity.getPackageName()+".updates",installFile);
            activity.startActivity(new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(apk).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE,true));
        }catch(Exception e){error("Android kurulum ekranı açılamadı.");}});
    }
    public boolean result(int request,int result,Intent data){if(request!=FOLDER)return false;if(result==Activity.RESULT_OK&&data!=null&&data.getData()!=null){try{
        int grants=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);if(grants!=(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))throw new IOException();Uri uri=data.getData();activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        AppUpdates.prefs(activity).edit().putString("folder",uri.toString()).putString("folderLabel",android.provider.DocumentsContract.getTreeDocumentId(uri)).commit();state();
    }catch(Exception e){error("Yedek klasörüne okuma/yazma izni verilmedi.");}}return true;}
    private void notificationPermission(){if(Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)activity.runOnUiThread(()->activity.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},77));}
    private void state(){try{host.send("updates-state",AppUpdates.state(activity));if(activity.getIntent().getBooleanExtra("update-review",false)){activity.getIntent().removeExtra("update-review");host.send("updates-review",new JSONObject());}}catch(Exception e){error("Güncelleme bilgisi okunamadı.");}}
    private void error(String value){host.send("error",UpdateText.text(activity,value));}
    public void close(){if(backupProgress!=null){backupProgress.dismiss();backupProgress=null;}io.shutdown();}
}
