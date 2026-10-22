# INSFAV — salt okunur, kalıcı kontrol noktalı thumbnail taraması

Kapsam yalnızca normal **Rescan Thumbnails** indeksleme yoludur. Fetcher'ın
worker ayarı, kuyruk veritabanı, indirme politikası, WebP adlandırması, 128/256
uyumluluğu ve görsel sunma yolu korunur. Uygulama sürümü bu çalışma dalında
5.0.0 / build 9 olarak kalır; bu belge imzalı bir yayın yapıldığını belirtmez.

## Kaynak incelemesi ve değişiklikler

| Dosya / metot | Eski davranış | Yeni davranış | İlgili kanıt |
|---|---|---|---|
| `LocalImages.scan()` | Tek SAF sorgusundan sonra her yeni dosyayı bounds ve piksel kontrolü için ayrı açar; bütün adayları RAM'de tutar; yalnızca son JSON kaydedilirdi. | Tek metadata cursor'ı, en fazla 256 adaylık çalışma grubu, SQLite kontrol noktaları ve tamamlanınca atomik JSON. | `LocalImagesTest`, `ThumbnailScannerTest`, `RescanBenchmarkTest` |
| `ImageCodec.inspect()` (yeni), `bounds()`, `decode()` | Bounds ve doğrulama için tipik thumbnail iki kez açılırdı. | 64 KiB sınırlı geri sarma tamponuyla tipik dosya bir kez açılır. Bounds ve örneklenmiş piksel doğrulaması korunur. Büyük başlıkta güvenli eski iki açma yoluna dönülür. Ortak bounds/örnekleme kontrollerinin davranışı korunur. SAF açılışına CancellationSignal aktarılır. | Küçük WebP/PNG/JPEG, kesilmiş içerik, büyük JPEG başlığı ve önceden iptal testleri |
| `ThumbnailScanner.scan()`, `process()` (yeni) | Tek iş parçacığı tüm adayları seri doğrulardı; yinelenen ve bozuk adaylar yeniden okunurdu. | Metadata sıralı okunur; yalnızca yeni/değişmiş/belirsiz adaylar en fazla iki worker ile doğrulanır. Değişmeyen adayların tamamı yeniden kullanılır. | 1/2/4 worker deneyi; tekrarlanan adayın değişmesi; eksik metadata; geçici FileNotFound |
| `ThumbnailScanIndex.begin/lookup/save/catalog/complete()` (yeni) | Kalıcı ara sonuç yoktu. | Özel `thumbnail-scan.db`, WAL ve hazırlanmış SQLite ifadeleri; 256 kayda kadar tek transaction. Değişmeyen kayıt yeniden yazılmaz. Sıcak taramada yalnızca başlangıç/bitiş transaction'ı gerekir. RAM'de uzun SAF kimlikleri yerine yerel sayısal rowid'ler tutulur. | Transaction rollback, kesinti sonrası DB açma/devam, sıcak taramada iki transaction |
| `LocalImages.publish()` | En fazla yaklaşık 100 ms aralıkla ilerleme. | En fazla yaklaşık 250 ms aralıkla ilerleme; tarama ana UI thread'inde çalışmaz. Başlangıç/sonuç olayları ayrıca yayımlanır. | Sahte saatle kesinti testi; browser ilerleme testi |
| `LocalImages.scan/clearIndex/storageAudit()` | Explicit rescan, yazma izni varsa Favorites dışında kalan WebP'leri silebilirdi. Clear Index yalnızca JSON'u temizlerdi. | Rescan hiçbir kaynak dosyaya yazmaz/silmez. Clear Index yalnızca özel JSON + kontrol noktalarını temizler. SQLite boyutu depolama sayacına eklenir. | Yazma izni varken yetim WebP'yi koruma; Clear Index sonrası yeni doğrulama; 15.000 dosya hash + lastModified kontrolü |
| `MainActivity` / `rescan`, `startScan()`, `logs` | Rescan için yazma izni istenebilirdi. | Rescan salt okunur; ek yazma izni istemez. Diagnostics'e yalnızca sayısal scan I/O metrikleri eklenir. | Native salt okunur tarama testleri + browser komut testi |
| `web/app.js`, üretilmiş app asset/map/manifest | Ayarlarda silinen yetim dosya sayaçları gösterilirdi. | Salt okunur indeksleme açıklaması ve kontrol noktası indeksi boyutu gösterilir. Görsel gösterme akışı değişmez. | `tests/browser.cjs` |
| Test APK'sı / `RescanDocuments`, `RescanDeviceBenchmark`, `PoolInstrumentation` | Gerçek Binder üzerinden 15.000 dosyalık normal Rescan ölçümü yoktu. | Yalnızca test APK'sındaki ayrı, salt okunur sağlayıcıyla cold/warm/restart, kaynak bütünlüğü ve PSS anlık ölçümü alınır. | GitHub Android 14 emülatör işi; sonuç `rescan-15000.json` |
| CI / `android.yml`, `tools/rescan-report.py`, `tools/emulator-pool.sh` | Rescan önce/sonra ölçümü paketlenmiyordu. | Aynı test harness'i değiştirilmemiş `ba7812f` üzerinde ve yeni kodda çalışır; başarısız/eksik ölçüm raporlanmaz, iş başarısız olur. | Raporlayıcının dört Python testi ve ham JUnit çıktıları |

## Güvenlik, artımlı tarama ve devam

- Mevcut `thumbnail-index.json` şeması 1 ve `profiles` alanları korunur. SQLite,
  tarama önbelleği ve kontrol noktasıdır; Fetcher veritabanının yerine geçmez.
- Tamamlanan batch'ler kalıcıdır. Kullanıcı iptali/uygulamanın kapanması sonrası
  yeni tarama metadata'yı yeniden listeler, değişmeyen doğrulanmış dosyaları
  yeniden açmadan devam eder. Ani süreç ölümünde son commit edilmemiş batch'in
  en fazla 256 doğrulaması tekrar gerekebilir.
- SAF'ta taşınabilir, güvenilir bir cursor devam imleci veya değişiklik token'ı
  yoktur. Bu nedenle explicit Rescan hâlâ tek tam metadata listelemesi yapar;
  sıra/offset atlayarak dosya kaybetmez. Normal uygulama yeniden açılışı mevcut
  JSON indeksini kullanır ve tüm klasörü yeniden listelemez.
- URI, dosya adı, boyut ve pozitif son-değiştirme zamanı birlikte eşleşmelidir.
  Eksik boyut/zaman veya geçici FileNotFound sonraki taramada tekrar denenir.
  Sağlayıcı yanlış ama değişmeyen metadata bildirirse içerik değişimini okumadan
  tespit etme garantisi yoktur. Clear Thumbnail Index bu özel önbelleği de temizler.
- Cursor null/başarısız veya `EXTRA_LOADING` / `EXTRA_ERROR` ise tamamlanmış
  genel indeks değiştirilmez; eksik liste eski kayıtları temizleyemez. Yalnızca
  başarılı tam tarama yerel indeksin artık bulunmayan kayıtlarını kaldırabilir.
  Kaynak dosyalar hiçbir durumda kaldırılmaz.
- OOM yeni doğrulamaları iptal eder; mümkün olan tamamlanmış kontrol noktaları
  ve son genel indeks korunur. Kaynak stream'leri, bitmap'ler ve executor kapatılır.
  Sağlayıcı Android iptal sinyalini dikkate almıyorsa anında I/O kesilmesi
  garanti edilemez.
- Diagnostik scan metrikleri: metadataQueries, inspected, reused, workers,
  peakValidations, batchSize, transactions, resumed. Hesap adı, URI, cookie veya
  HTML içermez.

## Ölçümlerin anlamı ve tekrar üretme

`RescanBenchmarkTest` **15.000 gerçek sentetik dosya** oluşturur; iki küçük
128/256 WebP örneği çoğaltılır. SAF açma gecikmesi 1 ms olarak modellenir.
Ölçüme dosya oluşturma ve hash kontrol süresi dahil değildir. Önce/sonra aynı
harness ile çalışır; donanım, canlı profil veya kullanıcının klasörünün hızı
olarak gösterilmez. İlk açma sayısı 30.000 → 15.000; sıcak açma sayısı her iki
tarafta 0 olmalıdır. Toplam SAF sorgusu 3 → 2 olur (bir erişim sorgusu + bir
çocuk listeleme sorgusu). Değişmeyen yinelenen/bozuk adaylar için de sıcak açma 0'dır.

`RescanConcurrencyTest` 1.024 sentetik dosyada 2 ms açma gecikmesi ile 1/2/4
worker'ı karşılaştırır. Dört worker bu fixture'da daha hızlı olabilir; gerçek
sağlayıcının kapasitesi bilinmediği için üretim yolu iki worker ile sınırlıdır.
Bu, tüm cihazlar için optimum sayı iddiası değildir. Fetcher worker tercihi
değiştirilmez. Pixel doğrulaması en fazla 128 örnekleme hedefi kullanır; tarama
kalıcı bitmap/cache üretmez.

Tam testler: `./gradlew :app:testDebugUnitTest` ve `./gradlew :app:lintDebug`.
CI'da ham sonuçlar `measurements/rescan-before.xml`, yeni JUnit benchmark
çıktıları ve `measurements/rescan-before-after.json` içindedir. Emülatör ölçümü
gerçek Android SAF/Binder kullanır, yapay gecikme eklemez; fiziksel cihaz veya
gerçek Instagram testi değildir. PSS önce/sonra anlık örneklerdir, peak bellek
iddiası değildir. Kullanıcının kaynak dosyalarına bu testlerde erişilmez.
