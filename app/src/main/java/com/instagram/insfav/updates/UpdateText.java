package com.instagram.insfav.updates;
import android.content.Context;
import java.util.Map;
/** Updater native prompts follow the application's saved presentation language. */
final class UpdateText {
    private static boolean tr(Context c){String language=c.getPackageName().endsWith(".plus")?c.getSharedPreferences("plus-ui",0).getString("language","system"):c.getSharedPreferences("insfav-ui",0).getString("language","en");return language.equals("tr")||language.equals("system")&&c.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("tr");}
    private static final Map<String,String> EN=new java.util.HashMap<>();
    static {
        EN.put("Yedekleme zaten sürüyor.","A backup is already running.");
        EN.put("Yedek kaydediliyor ve doğrulanıyor…","Saving and verifying the backup…");
        EN.put("Güncelleme kontrolü/indirmesi tamamlanamadı. Tekrar deneyin.","Update check/download could not finish. Try again.");
        EN.put("Güncelleme tercihleri kaydedilemedi.","Update preferences could not be saved.");
        EN.put("Güncelleme indirilemedi veya APK doğrulaması başarısız. Paket ve imza mevcut kurulumla aynı olmalı.","Download or APK verification failed. Package and signer must match the installed app.");
        EN.put("Güncelleme yükleme izni","Update installation permission");
        EN.put("Android ayarlarında bu uygulamanın güncelleme yüklemesine izin verin. Ardından güncelleme butonuna yeniden dokunun.","Allow this app to install updates in Android settings, then tap the update button again.");
        EN.put("Ayarları aç","Open settings");
        EN.put("Vazgeç","Cancel");
        EN.put("Önce güncelleme yedeği klasörünü seçin. Kurulum başlatılmadı.","Choose a backup folder first. Installation has not started.");
        EN.put("Güncelleme öncesi tam veri yedeği","Full data backup before updating");
        EN.put("Yedek tamamlanamadı. İndirmeyi/taramayı duraklatın, HD dönüşümünün bitmesini bekleyin ve klasör erişimini kontrol edin. Kurulum başlatılmadı.","Backup did not finish. Pause retrieval/scanning, wait for HD conversion and check folder access. Installation has not started.");
        EN.put("Kurulum başlatılamadı. İşlemleri duraklatın; paket, imza ve dosyayı kontrol edin.","Installation could not start. Pause active work; check the APK, package and signer.");
        EN.put("Yedek hazır. Kurulum için uygulamaya dönüp güncelleme butonuna dokunun.","Backup is ready. Return to the app and tap the update button to install.");
        EN.put("Android kurulum ekranı açılamadı.","Could not open the Android installer.");
        EN.put("Yedek klasörüne okuma/yazma izni verilmedi.","Read/write access to the backup folder was not granted.");
        EN.put("Güncelleme bilgisi okunamadı.","Update information could not be read.");
        EN.put("Güncelleme var · Yüklemeden önce yenilikleri inceleyin","Update available · Review changes before installing");
    }
    static String text(Context c,String source){return tr(c)?source:EN.getOrDefault(source,source);}
    private UpdateText(){}
}
