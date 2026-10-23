# INSFAV Plus 1.0.0 — uygulama ve doğrulama raporu

INSFAV Plus bağımsız, tek Android uygulamasıdır: `com.instagram.insfav.plus`,
`VERSION_NAME=1.0.0`, `VERSION_CODE=1`, minSdk 26 / targetSdk 35. Java namespace
`com.instagram.insfav` olarak korundu. Debug sürüm adı `1.0.0-debug` olur.
Kullanıcının netleştirdiği ölçü **60×60 CSS px avatar, 65 px hesap satırı**dır.

## 1. Depo, geçmiş ve PR

- Hedef: <https://github.com/BlackWare-AI/INSFAV-Plus>;
  İlk teslim PR'ı: <https://github.com/BlackWare-AI/INSFAV-Plus/pull/1>.
  Bu PR `74d3ee9` ile Plus main'ine birleştirilmiştir. Son güvenlik/logo
  değişikliklerinin karşılaştırması:
  <https://github.com/BlackWare-AI/INSFAV-Plus/compare/main...plus/1.0.0>.
- Native güvenlik kontrol noktası: `357b6f7`; teslim dokümanları ve CI düzenlemesi
  sonraki mantıklı commit'tedir (`bc9e4ad` arayüz/logo). Kesin teslim SHA'sı
  son kullanıcı raporunda verilir. İlk teslim PR'ı main'e alınmıştır; yeni
  güvenlik ve logo değişiklikleri `plus/1.0.0` dalından ayrı bir takip PR'ında
  incelenir. Bu takip PR'ı otomatik birleştirilmez.
- Kaynak `0a4ba915418e0652618879d4af2c0745ef9ca4f0` değiştirilmedi. Başlangıçta eski `main` bu SHA'daydı. Sonraki salt okunur
  kontrolde uzak `main` `e06e6e4` olarak gözlendi; bu kaynak değişikliği Plus'a
  alınmadı. Bu çalışmada eski depoya push/PR/merge/release/sync yapılmadı. Sabit
  baseline ve gözlem zamanı `verification/source-read-only-audit.json` içindedir. Yeni depo `fork=false`, `parent=null` olarak kontrol edildi.
- Yazılabilir origin yalnız Plus'tır. Kaynak remote push adresi
  `disabled://source-read-only`; yerel pre-push kontrolü yalnız Plus hedefini kabul
  eder. Eski Git geçmişi, lisanslar, testler ve APK'lar tam Git bundle içinde
  korunur. Kaynak etiketi bundle içinde, güvenli `baseline/plus-import` etiketi
  Plus geçmişindedir; eski yürütülebilir workflow ağacı remote ref olarak açılmadı.

## 2. Fazların sonucu

| Faz | Uygulanan kapsam | Kontrol noktası |
|---|---|---|
| 1 | Bağımsız import, provenance, Plus adı/kimliği/sürümü/ikonu, eski workflow izolasyonu | `e751069` |
| 2 | 60 px avatar, bitişik 65 px satır, virtual koordinatlar ve erişilebilir adlar | `d976254` |
| 3 | Kapalı profil aday vetosu, timeout/hata izolasyonu, FGS timeout ve saat açıklaması | `7115d42` |
| 4 | Kalıcı metadata günlüğü, toplu snapshot, sınırlı görsel akışı, olay tabanlı bekleme, 1 Hz durum, Resume ve iptal | `113f081` |
| 5 | Ayar/temizlik/backup menüleri, birleşik diagnostics, TR/EN ve Türkçe görünen-ad araması | `4b4e7e8` |
| 6 | Eski JSON ve doğrulanmış eski paket AAD ayar migrasyonu, kaynak dosyaları koruma | `00a9e95` |
| 7 | Workflow/script/APK arşivi, tekrar üretilebilir web/i18n/font çıktıları, CI/publish ve belgeler | `08b348e` |
| 8 | Hata/lint/çağrı grafiği incelemesi; davranıştan ayrı Java biçimlendirme | `697f7f9`, `c8c12b6` |
| 9 | Salt okunur thumbnail envanteri, sonuç filtreleri, ölçülmüş geliştirici ekranı, sınırlı geçmiş, başlangıç kurtarma | `85cb932` |
| 10 | A/B ölçümler, coverage, lifecycle/HD/migrasyon regresyonları, APK ve birlikte kurulum testleri | `727d29c`, `c6b0e63` |
| 11 | Yalnız Plus PR'ı, test artifact dağıtımı, parametrik imzalı yayın kapıları ve nihai rapor | Bu teslim |

Kod ve otomasyon kapsamı tamamlandı. Resmî imzalı yayın yapılmadı; fiziksel cihaz,
canlı Instagram ve aşağıda belirtilen sistem sınırları ayrıca doğrulanmalıdır.

## 3. Önemli dosyalar

- `app/build.gradle`, `version.properties`, `AndroidManifest.xml`: ayrı uygulama
  kimliği, mevcut SDK'lar ve test kimliği; orijinal `signingConfigs` bloğu byte
  karşılaştırmasıyla aynı. Yeni Android modülü ve çalışma zamanı kütüphanesi yok.
- `web/style.css`, `web/core.js`, `web/app.js`: avatar/virtual list, tutarlı arama,
  erişilebilirlik, kalıcı DOM kartları, dil/ayar/envanter/filtre/kurtarma arayüzü.
  `web/i18n/catalog.tsv` ortak metin kaynağı; Android strings/arrays ve paketli
  Web katalogları `npm run web` ile üretilir.
- `Store.java`, `IndexJournal.java`, `InsfavApplication.java`: mevcut `fetch_meta`
  tablosunda artımlı güncelleme; 64 değişiklik veya 60 saniyede AtomicFile snapshot,
  terminal flush, crash sonrası tekrar oynatma ve güvenli onay watermark'ı.
- `LocalImages.java`, `SourceBytes.java`, `ImageCodec.java`: ortak sınırlı encoded
  akış, 64 MiB kaynak sınırı, küçük/büyük okuma slotları, iki validation worker,
  ölçümle seçilmiş 256 kayıt checkpoint, Cancel ve ölçüm. Kaynağa yazma/silme/kopyalama yok.
- `ProbeDecision.java`, `WebViewProfileProvider.java`, `profile-probe*.js`,
  `QueueEngine.java`, `FetchBroker.java`, `FetchService.java`, `ManualFetch.java`:
  hata öncelikleri, timeout, olayla uyanma, profil/global hata ayrımı, OOM ve
  sistemin erteleme sınırlarına uyum.
- `SettingsArchive.java`, `MainActivity.java`: açık eski uygulama import'u,
  GCM/AAD kontrolü, yeni Keystore ile cookie şifreleme, durdurulmuş geri yükleme,
  rollback doğrulaması, gerçek SAF izinlerinin tekrar alınması. Aynı tree altında
  farklı alt klasöre geçiş bile eski görsel metadata'sını geçersiz kılar.
- `OperationHistory.java`, `ThumbnailInventory.java` ve ilgili testler: sınırlı,
  sabit kodlu geçmiş ve sayfalı, silme komutu bulunmayan yerel envanter.
- `.github/workflows/{ci,publish}.yml`, `tools/{verify,verify-apk,coexist,benchmark}.py`:
  Plus'a sınırlandırılmış doğrulama, emülatör ve güvenli artifact/yayın denetimi.

## 4. Korunan işlevler ve veri sözleşmeleri

Koleksiyon JSON biçimi, ham/ek alanlar, sıra, aynı hesabın farklı listelerdeki
üyeliği, `favorite_page_pref` ve desteklenen InstaPro import'u korunur. Mevcut
SQLite şema sürümleri değişmedi. Thumbnail adları, 128/256 WebP ve standart cookie
şifreleme biçimi korunur. Kaynak görsellerin byte'ları ve tarihleri fixture
parmak izleriyle kontrol edildi. Mevcut PNG/JPEG/WebP yalnız görüntülenir; HD
viewer yerel PNG yanında fazladan WebP üretmez ve kapanınca RAM'i bırakır.

HD/view-remote, Fetcher, cookie, schedule, WorkManager, bildirim, SAF, virtual
liste, Usage/Load/Reset Count, Add/Delete List bilgileri, worker ayarları ve 1–10
WebView arayüzü, çözünürlük sayacı, missing kullanıcı adlarını kopyalama,
JSON import/export, Rescan/Retry/Pause/Resume/Stop korunur. Eski kayıtlı worker
konfigürasyonlarını okuyabilen native sınırlar kaldırılmadı.

Login/challenge/rate-limit kapalı sayfa kararından önceliklidir. Geçerli görsel
adayı veya daha önce görülmüş aday varsa hata metni tek başına kalıcı kapalı hesap
kararı vermez. HTTP 200/404 tek başına bu kararı oluşturmaz. Profil timeout'u
`WEBVIEW_TIMEOUT` olarak tekrar denenebilir kalır. Profil RuntimeException'ı tek
hesabı etkiler; storage/izin/SQLite ve OOM havuzu güvenle durdurabilir. Varsayılan
pencere yerel 00:00–08:00, açıklamalar ve sonraki uygun zaman 24 saat biçimindedir.

60 px avatar tüm liste/arama/klasör satırlarında cover ile gösterilir; placeholder
büyütüldü. Metin fontları büyütülmedi. İlave hesap aralığı yoktur. Sabit 65 px
yükseklikte büyük yazı görünür olarak kırpılabilir; tam ad ekran okuyucuya verilir. Adın üzerine basılı tutma, bağlam menüsü
veya Shift+F10 ile kaydırılabilir tam metin penceresi açılır. 320×480 ekranda
30 px metin ve 30 karakter kullanıcı adıyla erişim ayrıca test edilir.

## 5. Arşivleme ve sadeleştirme

On bir eski workflow aktif klasörden `docs/archive/legacy-workflows` içine
taşındı. Yalnız eski workflow/raporlarla kullanılan tek seferlik araçlar ve
testleri `legacy-tools` içinde, tarihî raporlar `technical-reports` içindedir.
Git takipli eski APK'ların birebir içeriği bundle'da; checksum ve açık sertifika
referansları `release-artifacts.json` içindedir. Aktif kaynak ağacında APK yoktur.
Kullanılmayan eski ikon varyantları arşivlendi; kullanılmayan `ic_fetch` kaldırıldı.
Önceki ikon ve logo arşivde korundu. Kullanıcının verdiği JPEG (1280×1198,
43.009 byte) yeniden kodlanmadan launcher, açılış, About ve README
markalamasında kullanılır. Tek kaynak `web/static/logo.jpg` üzerinden üretilir.

`image-failed`, HTTP/yerel viewer yolları kullanım bulunduğu için korunur.
MainActivity switch'i map'e taşımak bakım kazancı sağlamadığı için korunur.
Mevcut Fetcher sınıfları tekrar bölünmedi. SharedPreferences kalıcı alanlarında
senkron commit korunur. Lint suppress nedenleri yerel olarak belgelidir;
genel lint baseline eklenmedi. Inter alt kümesi tüm mevcut Unicode cmap ve
glif/advance eşitliği ile Türkçe kapsamını korur; Chromium font testi geçer.

Orphan görünümünde silme kullanıcı tercihi doğrultusunda uygulanmadı. Ekran
yalnız önizleme/listedir. Sonuç filtreleri koleksiyon sırasını veya üyeliğini
değiştirmez; Refresh ile açıkça güncellenen bir görünüm snapshot'ı kullanır.

## 6. Gerçek performans sonuçları

Üç tekrarlı aynı host A/B sonuçlarının kapsamı ve ham sayıları
[PERFORMANCE.md](../PERFORMANCE.md) ve [performance](verification/performance)
altındadır. Host iki CPU kotası/8 GiB sınırı, JDK 17, Robolectric; ölçüm sırasında
coverage agent ve emülatör kapalıdır. Host dosya sistemi memory-backed olduğundan
bu sayılar fiziksel NAND yazımı veya pil tüketimi değildir.

| İş yükü | Önce → sonra | Sonuç |
|---|---|---|
| 15.000 kayıt üzerinde 64 metadata güncellemesi | 1.111 → 266 ms | Yaklaşık %76 daha kısa |
| Public JSON snapshot | 64 → 1; 154.660.287 → 2.421.238 byte | Snapshot içeriği %98,4 azaldı |
| SQLite günlüğü dahil süreç `wchar` | 154.660.287 → 3.615.158 byte | Yaklaşık %97,7 daha az; küçük yazım çağrısı sayısı arttı |
| Boş dispatcher / yaklaşık 1 saniye | 50 wait → 0 wait | Host JVM ölçümü; Android enerji ölçümü değil |
| Değişmeyen 20 Resume yanıtı | 24.992.020 → yaklaşık 18.680 byte; 740 → 66,6 ms | Revision/scope değişmediyse hafif yanıt |
| Tek profil güncellemesi | 1.249.601 → yaklaşık 1.061 byte | Tam profil haritası yerine patch |
| 2,2 sn progress + 1,1 sn drain | 11 → 3 normal gönderim | Kritik iki ardışık olay hemen iletilir |
| Host 15.000 WebP Cold / Warm scan | 12.653 → 12.348 / 467 → 443 ms | Warm dağılımı çakışır; sağlam warm hızlanması iddiası yok |
| Eşit 60 px avatarla 10k / 15k render p95 | 4,1 → 4,2 / 4,5 → 4,0 ms | Küçük karışık değişimler; frame p95 yaklaşık 16,8 ms |
| Debug APK | 2.956.131 → 3.092.065 byte | Yaklaşık %4,60 artış; iki dil ve yeni işlevlerin maliyeti |

Render sonuçlarındaki küçük karışık farklar hızlanma diye sunulmadı. Virtual algoritma korunur, DOM ve görsel iş
sınırları test edilir. 15k JavaScript heap örneği eşit-avatar bazında 15,24 →
15,43 MB'dır; bu native/GPU/Android toplam RAM peak'i değildir. Yeni bitmap LRU
veya thumbnail disk cache yoktur. Ertelenmiş arama indeksleme denemesi karışık
sonuçlar üzerine geri alındı. Türkçe sorguyu bir kez normalize etmek 15k ad
aramasında 12,9/19,1 → 3,2/1,4 ms verdi; hesap kimlik normalizasyonu değişmedi.

API 35 gerçek Binder SAF fixture taraması önceki başarılı CI'da 15.000 dosya için
27.344 ms cold / 1.426 ms warm; 15.000 / 0 kaynak açma, iki worker, 512 checkpoint,
sıfır kaynak yazımı ve değişmeyen byte/tarih sonuçlarını verdi. Önce/sonra PSS
55.164/216.759 KiB örnekleridir, peak değildir. Host ile hız karşılaştırılmaz.
SQL/JSON/query/open/decode süreleri ayrı raporlanır; worker süreleri örtüşür.

## 7. Test sayıları, başarısız denemeler ve coverage

Başlangıç: 222 Java ve 11 core testi başarılı; yedi eski Chromium script'i uygun
baseline ortamıyla geçti. Son tam yerel koşum: **272 Java, 16 core testi ve dokuz
ayrı Chromium fonksiyonel script'i başarılı; sıfır başarısız/hatalı/atlanmış Java
testi**. Debug ve AndroidTest APK derlemesi başarılıdır. Chromium avatar matrisi
10/1.000/10.000/15.000 hesap, farklı ekranlar, uzun adlar, eksik/256 WebP, klasör,
hızlı scroll, arama ve alt çubuk koordinatlarını kapsar. Probe, dil, focus, font,
envanter ve bootstrap hata testleri ayrı script'lerdir.

JaCoCo 0.8.13: **4.195/6.271 satır = %66,90**, **2.134/3.805 branch = %56,08**.
Generated R/BuildConfig dışarıdadır; Robolectric uygulama Java kodu ölçülür.
HTML/XML CI artifact'ine eklenir. Kapsama tam değildir ve cihaz coverage'i değildir.
Faz bazında komut exit'leri [verification](verification/README.md) altındadır.

Önceki başarısızlıklar gizlenmedi: 512'ye çıkan checkpoint için eski 256 beklentili
testler güncellendi; probe minifier parse hatası giderildi; bellek baskısındaki
yerel koşum sonlandırılıp seri koşum yapıldı (exit 143); stale AAPT incremental
kaynak ve yeni test fixture derleme hataları düzeltildi; settings rollback testinin
gerçek commit sırası kontrolü düzeltildi. İlk PR verify işi, kaldırılmış SDK
`tools` paketini setup adımında istediği için testlerden önce başarısız oldu;
paket listesi platform-tools/platform35/build-tools35 olarak açıkça sabitlendi.
JaCoCo'nun tüm build ağacından executionData aramasıyla oluşan örtük AndroidTest
bağımlılığı, tam `.exec` yolu seçilerek düzeltildi. Son tam yerel kontroller geçmiştir. Bir sonraki CI koşumunda runner
CHROME_BIN üzerinden sistem Chrome'unu seçti ve tarayıcı uygulama yüklenmeden
SIGTRAP ile sonlandı; açık HTTP test sunucusu nedeniyle süreç bekledi. Koşum iptal
edildi. CI Playwright tarayıcısına sabitlendi, UI testinin eski single-process
bayrakları kaldırıldı; her script için 120 saniye hata veren süreç sınırı eklendi.

Yerel yazılım emülatöründeki **orijinal baseline** instrumentation denemesi process
crash ile bitti; uygulama kaynaklı olduğu kanıtlanmadı ve başarı sayılmadı.
Plus API 35 CI offline fixture koşumu başarılıdır: altı worker/delay senaryosu,
Home/ekran kapalı/task removal, bildirim, clipboard ve 15k SAF. Bu eski başarılı
job'ın kesin commit/run/artifact bilgisi `emulator-api35/provenance.json` içindedir.
Nihai commit'in verify/emulator/birlikte-kurulum sonucu PR Checks'ten izlenir;
son kullanıcı raporu bu son koşumun sonucunu ve artifact bağlantısını verir.

## 8. Lint ve biçimlendirme

Son `lintDebug`: **0 hata, 0 uyarı**. Ara fazlarda 31–40 uyarı vardı;
ApplySharedPref kalıcı commit nedenleri, uygulama-context singleton'ları, yerel
JS gereksinimi, sabit WorkManager sürümü ve eski arama ellipsis'i için sınırlı
açıklamalar vardır. Diğer düzeltilebilir ikon/API/locale uyarıları giderildi.
95 Java dosyasının google-java-format 1.25.2 çıktısı davranıştan ayrı commit'tir;
son ek Java değişiklikleri de aynı formatter kontrolünden geçer.

## 9. APK ve imza

Test APK `com.instagram.insfav.plus`, code 1, `1.0.0-debug`, debuggable=true,
geçerli debug imzalıdır. Yerel dosya 3.092.065 byte; SHA-256 ve açık sertifika
digest'i [debug-apk.json](verification/debug-apk.json) içindedir. CI artifact'inin
kendi checksum/sertifika JSON'u vardır; onunla eşleştirilmelidir. İki build farklı
debug sertifikaları kullanabilir. AndroidTest APK yalnız test fixture'ıdır;
uygulama dağıtımı tek Plus APK'dır.

Resmî release yayınlanmadı, yeni release anahtarı üretilmedi, eski imza anahtarı
istenmedi. Plus release workflow'u kullanıcıca sağlanmış güvenli ayrı anahtar,
test/emülatör geçişi, paket/sürüm/build/signature/non-debuggable kontrolü ve artan
versionCode gerektirir. Anahtar/secret/cookie çıktısı rapora veya Git'e yazılmaz.

## 10. Bilinen sınırlar ve fiziksel cihaz doğrulaması

- Fiziksel telefon ve canlı Instagram testi yapılmadı. Login/challenge/rate-limit,
  DOM değişiklikleri ve gerçek ağ gecikmesi fixture dışı doğrulanmalıdır.
- Gerçek altı saatlik Android 15 FGS timeout'u bekletilmedi. `onTimeout` callback,
  kalıcı kuyruk, erteleme ve bildirim durumu unit testlidir; Home/ekran kapalı/task
  removal Android 35 emülatörde testlidir. Force Stop sonrası yeniden başlama
  garantisi verilmez; OS/WorkManager kısıtlarını aşan mekanizma eklenmedi.
- Toplam Android RAM peak'i, gerçek idle pil/CPU ve yavaş üretici SAF provider'ı
  ölçülmedi. Kaynak/yanıt byte sınırları ve eşzamanlılık unit testlidir. Eski
  uygulamaya karşı cihaz üzerinde tam cold/warm A/B yapılmadı.
- 65 px sabit satırda yüksek font ölçeğinde görünen metin kırpılması seçilmiş
  davranıştır; TalkBack ve OEM yazı ölçekleri fiziksel cihazda gözden geçirilmelidir.
- Android Keystore/SAF izinleri uygulama UID'sine aittir. Emülatör/host kriptografi
  testleri üretici donanım Keystore testi değildir. Settings rollback'i
  doğrulanamazsa açık hata gösterilir ve Fetcher durdurulmuş kalır.
- Debug artifact'leri saklama süresine tabidir; resmî release değildir. Farklı
  imzaya geçmeden Plus koleksiyon ve ayar yedeği alınmalıdır.

## 11. Eski uygulamadan geçiş

Eski INSFAV'ı kurulu bırakın, koleksiyon JSON'unu ve gerekirse parolalı ayar
yedeğini dışa aktarın. Plus test APK'sını ayrı uygulama olarak kurun. Plus
Backup / Restore'dan JSON'u import edin; mevcut `.thumbnails` klasörünü SAF ile
seçip Rescan çalıştırın. Dosyalar taşınmaz, yeniden kodlanmaz. Eski ayarlar için
yalnız **Import settings from original INSFAV** seçeneğini kullanıp parolayı girin.
Yeni UID için klasör iznini yeniden verin. Çalışma saatini/worker'ı gözden geçirip
Fetcher'ı kendiniz başlatın; import otomatik başlamaz. Ayrıntılar
[MIGRATION.md](../MIGRATION.md) içindedir.

## 12. Teslim bağlantıları

İlk teslim (birleştirildi): <https://github.com/BlackWare-AI/INSFAV-Plus/pull/1>.
Güncel güvenlik/logo takip PR'ı: <https://github.com/BlackWare-AI/INSFAV-Plus/pulls>.
Son kullanıcı raporu kesin PR ve commit bağlantısını verir.
Debug APK, test sonuçları ve coverage: PR Checks → **INSFAV Plus checks** →
**INSFAV-Plus-debug-test-and-coverage** artifact'i. Emülatör kanıtı ayrı
**INSFAV-Plus-emulator-evidence** artifact'idir. Son rapor kesin artifact URL'sini
verir. Resmî release bağlantısı yoktur; imza anahtarı sağlanmadan yayın yapılmaz.

## Son kullanıcı güvenlik gözden geçirmesi

15/30/45 saniye gerçek provider watchdog/callback kurallarıyla ve sanal saatle
karşılaştırıldı. Her eşikte normal/HD yollarında 16 bilinen-geçerli gecikmiş yanıt
gözleminde yanlış timeout sayıları 10/4/0 oldu. Örnek dağılımı sınır durumları
için seçilmiştir; canlı servis hata oranı değildir. Kaynak uygulamanın temkinli
45 saniyesi korundu; her timeout tekrar denenebilir WEBVIEW_TIMEOUT'tur.

Aynı Plus scanner/aynı 15.000 gerçek sentetik WebP/iki worker üzerinde, coverage
kapalı üç 256/512 çifti ölçüldü. Cold medyan 11.136,52 / 11.166,32 ms, warm medyan
149,35 / 182,78 ms. 512 toplam süreyi iyileştirmediği için 256 seçildi. Önceki
85cb932 emülatör sayıları 512 kullanan tarihî kontrol noktasıdır; güncel varsayılan
256'dır. Kaynak byte/tarihler değişmedi, warm source-open ve kaynak yazımı sıfır.

Altı mevcut journal/AtomicFile recovery testi toplulaştırma korunmadan önce tekrar
geçti. Yeni yedinci test snapshot eşiğinin altındaki 63 başarılı metadata kaydının
restart, kalıcı snapshot, onay ve ikinci restart sonrasında tamamını doğrular.
Fiziksel güç kesme testi yapılmadı.

Orphan görünümü yalnız yüklenmiş koleksiyonlara göre adayları gösterir. Eksik veya
tamamlanmamış import yanlış sınıflandırma yapabilir; UI bunu açıkça belirtir.
Koleksiyon yoksa tüm dosyaları orphan diye sınıflandırmaz. Silme komutu eklenmedi.

İlk yeni koşumda yalnız eski About testinin “logo yok” beklentisi başarısız oldu;
kullanıcının verdiği logonun gerçekten yüklenmesini ve JPEG boyutlarını doğrulayan
beklentiyle güncellendi. Son tam yerel koşumun 272 Java/16 core/dokuz Chromium
grubu, lint ve derlemeleri geçti. APK boyutu temiz derlemeyle karşılaştırıldı;
önceki incremental dex paketleme boyutları bu karşılaştırmada kullanılmaz.
