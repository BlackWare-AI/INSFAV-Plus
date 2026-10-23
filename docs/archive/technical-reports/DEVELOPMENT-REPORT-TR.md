# INSFAV 3.3.0 — Fetcher 0.4.0 entegrasyonu ve doğrulama

INSFAV 3.3.0 / build 5, `com.instagram.insfav`. Bağımsız Fetcher ve arşivlenmiş çalışan sürümü değiştirilmedi. Çalışan ana INSFAV kaynakları korunarak geliştirme yapıldı.

## Teslimat

[Debug APK, Android Studio proje ZIP'i, İngilizce README, Türkçe ayrıntılı rapor ve test kanıtları](https://github.com/BlackWare-AI/INSFAV/actions/runs/39498777613/artifacts/12300425300)

Paket açıldığında `INSFAV-debug.apk`, `INSFAV-Android-Studio-Project.zip`, `README.md`, `Development-Report-TR.md` ve `Verification.json` bulunur.

- Derlenen INSFAV kod commit'i: `dcf66c88a80f03712fe9e68a83bed32ea5a42de1`.
- Kaynak Fetcher deposu/dalı: `BlackWare-AI/INSFAV-Thumb-Fetcher`, `development/0.4.0`.
- Alınan Fetcher commit'i: `b3f03f2a30c36f89add5e954b9b121a7b9d899ae`.
- Çalışan 3.2.0 koruma commit'i: `ac3d1b0565acd4dfbf82763dbc4fec42c124fbc1`; `preserve-working-3.2.0` dalı.
- Doğrulama: https://github.com/BlackWare-AI/INSFAV/actions/runs/39498777613
- Debug APK: 2902577 bayt; SHA-256: `d25d83f2e32dee383d0106a2e24e611420931cc74d1fad94702a722ff478a470`.
- R8/resource shrinking etkin release derlemesi: 680722 bayt, imzasız.
- Önceki 3.2.0 referansı: debug 2.890.417 bayt, imzalı release 690.469 bayt. İmzalı ve imzasız boyutlar aynı ölçüm değildir.

Yerel çalışma ortamı bağlantı hatası verdiği için son teslimat GitHub doğrulama ortamında paketlendi. Yeni imzalı release APK bu teslimatta sunulmuyor. CI debug sertifikası önceki kurulumla farklı olabilir; mevcut verileri silerek kurulum önerilmez.

## Bulunan sorunlar ve düzeltmeler

Ana INSFAV broker'ı tek işlem kabul ediyordu. Kaynak 0.4.0 ayrıca `memoryClass / 32` ile kapasiteyi azaltıyor ve tamamlanan her işlemde ortak beklemeyi yeniden uzatıyordu. Yalnızca tamamlanma sonrası global beklemeyi kaldırmak da kısa profiller için yeterli değildi: tek global başlangıç takvimi onları yine seri bırakabiliyordu.

Son sistemde her işçinin kalıcı `workerNextAt` takvimi vardır. Minimum/maksimum değerler her işçinin kendi başlangıçları arasındaki aralıktır. Bekleyen bir işçi diğer hazır işçiyi tutmaz. Genel Retry-After, 429 ve oturum/erişim engelleri bütün havuzu duraklatır. İstenen işçi sayısını artırmak toplam istek hızını artırır; sunucu tarafından engellenmeme garantisi verilmez.

Requested, Effective ve Active WebViews; aktif hesap, hesap/dakika, ETA ve uygulama PSS bilgileri ayrıdır. Ayar 1–30 arasında kalıcıdır; varsayılan 1'dir. Bellek bütçesi yüzünden azaltılan gerçek üst sınır arayüzde görünür. Java memoryClass bölmesi kaldırıldı; kullanılabilir bellek/heap tahminleri, bellek baskısı ve renderer hatalarında kontrollü azaltma kullanılır. Bu tahminlerin fiziksel Samsung cihazında kalibrasyonu yapılmadı.

## Bileşenlerin durumu

| Bileşen | Sonuç |
| --- | --- |
| 0.4.0 WebView havuzu, yeniden kullanım ve ortak cookie lease | Aktarıldı; mevcut INSFAV oturumu ve HD önceliğiyle uyarlandı |
| DOM probe ve thumbnail codec | Aktarıldı; yeni çıktı 256×256 WebP kalite 78, merkez kırpma |
| Kuyruk/QueueEngine/broker | Ana JSON ve SAF ile uyarlandı; kalıcı işçi takvimleri eklendi |
| ConcurrencyPolicy | Gerçek bellek bilgileri ve açıkça belirtilen tahminlerle uyarlandı |
| Servis/WorkManager | Görev kaldırılınca gereksiz iptal önlendi; aynı havuzla kurtarma ve çalışma saatleri korundu |
| Hata politikası | Hesap bazlı atlama, sınırlı yeniden deneme, global güvenlik duraklatmaları ayrıldı |
| Bağımsız uygulamanın ikinci JSON/klasör seçimi ve görüntüleyicisi | Aktarılmadı; ana uygulamanın çalışan karşılıkları korundu |
| Her ağ hatasını doğrudan atlama yaklaşımı | Aktarılmadı; geçici hatalar RETRY_LATER ve sınırlı FAILED akışını kullanır |

404/açık erişilemezlik `SKIPPED_UNAVAILABLE`; eksik veya belirsiz fotoğraf `SKIPPED_NO_IMAGE` olur. Eksik fotoğraf silinmiş hesap kanıtı değildir. Geçici ağ/zaman aşımı hataları sınırlı yeniden denenir. Üç gerçek başarısız deneme `FAILED` olur. Global erişim engeli hesap deneme hakkını tüketmez. Atlanan/başarısız hesaplar açık kullanıcı isteğiyle yeniden denenebilir.

Eski 128×128 dosyalar geçerli kalır; legacy yenileme isteğe bağlıdır. Eski dosya yeni dosya yazılıp yeniden okunarak doğrulanmadan silinmez. Mevcut SAF klasörü, doğrulamalı rename/yedek/journal kurtarması ve yeniden taramasız indeks güncellemesi korunur. Kaynak koleksiyon JSON'una fotoğraf yolu eklenmez.

AMOLED, avatarların yuvarlatılmış dikdörtgen biçimi/boyutu, animasyonlar, About düzeni, logo, BlackWare ve OpenAI ChatGPT destek bilgileri korunur. Store veri katmanı değişmedi. JSON alanları/sıraları/üyelikleri ve usage/load/clear artırma ifadeleri korunur.

Yerel fotoğraf önce gösterilir. Mevcut HD seçicisi aynı oturumu öncelikli kullanır; gerçek boyut, zoom/pan ve geri dönüş korunur. HD kalıcı dosyaya yazılmaz. Kapatma iptal/token ve yerel byte referanslarını bırakır; bütün Chromium RAM'inin aynı anda fiziksel olarak sıfırlandığı iddia edilmez. HD probe hash'i: `12a483aefb190ed37aa14b37b58bf285de4cf2d6f72fdb0f1a238633d62f9bcb`.

## Testler

- Android/Robolectric: **97 test**, 0 başarısız, 0 hata, 0 atlanan.
- Chromium arayüz: **78 kontrol**; 10.000 kayıt, büyük liste, About/sayaçlar, Fetcher ve HD/zoom/geri dönüş dahil.
- Çekirdek JavaScript: 9 başarılı test; 1.000/5.000/10.000 kayıt senaryoları dahil.
- Sayaç karşılaştırması: önceki sürümle 6 aynı durum görüntüsü; native artırma ifadeleri değişmedi.
- Probe/HD fixture: 3 başarılı kontrol.
- Lint: 0 hata, 23 uyarı.
- Debug, optimize release ve instrumentation APK derlemeleri başarılı.
- Unit sahte-provider çakışması, gerçek WebView testi yerine sayılmadı.

## Android 14 — gerçek Chromium ölçümleri

Gerçek WebView oluşturma, HTTPS gezinme, DOM okuma ve üretimdeki CDN okuyucusu çalıştırıldı. Yanıtlar anonim çevrimdışı fixture'lardır; Instagram'a bağlanılmadı. DOM görsel gecikmesi 8 saniye, CDN okuma gecikmesi 1,4 saniyedir. Matris harici SD kart yazma hızını ölçmez; SAF ayrı arka plan testinde sınandı. PSS uygulama sürecinin işlem sonu ölçümüdür, ayrı renderer süreçlerinin toplam belleği değildir.

| Requested | Worker delay (s) | Effective | Peak WebViews | Peak accounts | Peak CDN readers | Accounts/min | App PSS MiB | Success/Skipped/Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | 1 | 1 | 1 | 1 | 5.94 | 68.14 | 10/0/0 |
| 2 | 1 | 2 | 2 | 2 | 2 | 12.51 | 68.84 | 10/0/0 |
| 5 | 1 | 5 | 5 | 5 | 5 | 30.68 | 71.07 | 10/0/0 |
| 10 | 1 | 10 | 10 | 10 | 10 | 35.94 | 74.71 | 12/0/0 |
| 30 | 1 | 15 | 15 | 15 | 15 | 52.06 | 77.10 | 17/0/0 |
| 5 | 25 | 5 | 5 | 5 | 5 | 29.77 | 76.12 | 5/0/0 |


Aynı on hesaplık kontrollü koşulda 5 işçi, 1 işçiye göre **5.17 kat** hesap/dakika sağladı. Beşli senaryoda beş gerçek WebView ve beş CDN okuyucusunun işlem süreleri çakıştı. 30 istenince bu emülatörde bellek bütçesi 15 etkin işçiye izin verdi; gerçekten 15 WebView ve 15 CDN okuyucusu gözlendi.

25 saniyelik ek senaryo da beş gerçek WebView'in aynı anda çalıştığını kanıtladı. **Bu ek senaryonun hız değeri ilk dalga ölçümüdür; 25 saniye aralıkla uzun süreli/sabit hız olarak yorumlanmamalıdır.** Aynı işçinin kalıcı bekleme takvimi unit testlerinde ayrıca doğrulandı. Bütün hızlar kontrollü fixture sonuçlarıdır; canlı Instagram garantisi değildir.

## Arka plan kanıtı ve sınırlar

Android 14'te gerçek foreground servis, gerçek Chromium ve sentetik SAF sağlayıcısı ile Home/görev kaldırma geçti: Home öncesi 0, Home sonrası 2 yazım görüldü; görev kaldırılmadan önce 2 yazım vardı, kuyruk kullanıcı tarafından duraklatılmadan **8/8** dosyayı tamamladı.

Foreground servis `stopWithTask=false` ve START_STICKY kullanır. Activity durması yalnızca Background Processing kapalıysa kuyruğu duraklatır. WorkManager aynı havuzla kalıcı kurtarma, sınırlı kabul pencereleri, çalışma saatleri ve cooldown kullanır. Gerçek süreç öldürme sonrası cihaz üstü kurtarma, Doze, ekran kapalı uzun süre, üretici pil yönetimi ve gerçek SD/cloud sağlayıcıları ayrıca doğrulanmalıdır.

Fiziksel Samsung cihazı ve canlı Instagram oturumu ile hız/HD testi yapılmadı. Kullanıcının önceki HD başarısı temel alındı; yeni kod regresyon fixture'larıyla sınandı. Force Stop, Android kota ve üretici kısıtlarının aşılabileceği iddia edilmez. Cookie'ler, kişisel JSON/fotoğraflar, özel loglar ve imzalama anahtarları depoya veya teslimata eklenmedi.
