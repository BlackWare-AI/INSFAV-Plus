# INSFAV 3.4.0 — Thumbnail, depolama ve bildirim denetimi

## Kapsam ve koruma

Başlangıç: INSFAV 3.3.0/build 5, `54b43f8151ded94ef0b6655c8575d519effe7fb6`. Kaynak Fetcher: `development/0.4.0`, `b3f03f2a30c36f89add5e954b9b121a7b9d899ae`. Bağımsız uygulama değiştirilmedi.

`Store.java`, `ImageCodec.java`, `Fetcher.java`, `FetchBroker.java`, `WebViewProfileProvider.java`, cookie/oturum katmanı, `ConcurrencyPolicy` ve `WorkerPacing` değiştirilmedi. HD JS işlevleri, `/full/`, `/remote-full/`, HD probe, zoom/pan, token/iptal ve byte temizleme korunur. `/thumb/` her iki kullanım için orijinal kaynak baytlarını sunar. JSON, üyelikler, sıralar ve sayaç veri kaynakları değişmedi.

## Eski ve yeni mimari

Önce: indekslenmiş SAF URI → global görsel kilidi → kaynak bounds/decode → 128×128 bitmap → kalite 82 WebP → `cache/local-thumbnails/<hash>.webp` → WebView. Sonraki aynı istek diskteki türetilmiş kopyadan okunuyordu. Herhangi bir indeks revizyonu bütün avatar URL'lerini değiştiriyordu. Sabit overscan 350 px ve `loading=lazy`, sanallaştırma ile ek yükleme gecikmesi oluşturabiliyordu.

Sonra: kullanıcı adı → kalıcı URI indeksi → aynı kaynak için eşzamanlı okuma birleştirme → orijinal dosya baytları → MIME türü doğru WebView yanıtı → 40×40 CSS/object-fit cover. Liste yolunda bitmap, resampling, yeniden kodlama ve uygulama disk kopyası yoktur. Farklı dosyalarda en fazla dört native okuma eşzamanlıdır. Birleştirme yalnızca uçuş halindeki encoded byte sonucunu paylaşır; kalıcı encoded/bitmap cache değildir. Kaynak PNG/JPEG/WebP ve eski 128 WebP desteklenir. WebView normal decode işlemini yapar.

HD yerel `/full/` önizlemesinin mevcut bounded decode/PNG davranışı değiştirilmedi; liste optimizasyonu bu yolu etkilemez. Scan doğrulamasının geçici bitmap decode işlemi de korunur; bu liste için küçültülmüş arşiv üretmez.

## İstek, DOM ve bellek politikası

İleri yönde normal kaydırmada 180–700 px, çok hızlı kaydırmada 240 px, arka yönde 100 px overscan; hız/yön bir sonraki render'da güncellenir. 71 px hesap satırı, sıra, konum ve koleksiyon davranışları korunur. En fazla 32 ayrılmış satır tekrar kullanılır. En fazla 64 browser Image girdisi tutulur; altı etkin görsel yüklemesi, görünür öğe önceliği ve ekran dışı bekleyen/etkin yükleme iptali vardır. Bunlar browser Image elemanlarıdır; native Bitmap cache eklenmedi. Gerçek decoder belleğini Chromium yönetir; 64 × 256 × 256 RGBA'nın teorik üst girdisi yaklaşık 16 MiB'dir, gerçek ölçülmüş PSS değildir. Büyük harici orijinaller için bu tahmin geçerli değildir; Chromium decoder davranışı fiziksel cihazda ayrıca ölçülmelidir.

Dosya sürümü URI/mtime/size metadata'sına dayanır; tek bir Fetcher yazımı yalnızca ilgili kullanıcıyı yeniler. mtime/size vermeyen sağlayıcılarda kontrollü tarama zamanı sürüme eklenir. Sağlayıcı yanlış sabit metadata veriyor veya dış araç tarihi/boyutu aynen koruyorsa içerik farkı metadata ile kanıtlanamaz; explicit Clear Image Cache browser Image girdilerini de bırakarak yeniden okuma sağlar. Manuel rescan korunur. Klasör her avatar isteğinde taranmaz. Liste kaynağı okuması ana thread'de yapılmaz. Fetcher.action içindeki JSON/SQLite/SAF hazırlığı da mevcut I/O executor üzerinde bırakıldı; yalnız bildirim izin penceresi UI thread'ine geçer. Servisin ilk foreground bildirimi, ana thread'de kuyruk SQL sorgusu yapmadan hazırlanır. ETA için bütün bekleyen kullanıcı adlarını her hesapta yeniden listelemek yerine mevcut gruplanmış sayaçlar kullanılır. Native stream'ler try-with-resources ile kapatılır. Geçici okuma hataları indeksi kalıcı okunamaz yapmaz; doğrulanmış FileNotFound ve kayıp izin ayrılır.

`WebSettings.LOAD_DEFAULT` ve sürümlü yerel URL'lerde beş dakikalık private browser cache kullanılır. Bu, kullanıcı tarafından izin verilen WebView browser cache davranışıdır; uygulama ikinci bir kalıcı thumbnail arşivi oluşturmaz. Browser kendi disk cache'inde orijinal yanıt tutabilir. Kaynak görüntü tekrar kodlanmaz. HD online yanıtları mevcut no-store başlıklarını korur; yerel /full/ ve uygulama asset yanıtları da açıkça no-store kullanır. Böylece thumbnail cache politikası HD önizlemesini diske taşımaz ve güncelleme sonrası eski JS paketi cache'ten gelmez. Clear Image Cache browser cache'i ve bilinen eski app kopyalarını temizler.

## Depolama ve sayaçların anlamı

Yalnızca `cache/local-thumbnails` içindeki SHA-256 isimli `.webp`, AtomicFile `.bak/.new` türevleri otomatik temizlenir. Bilinmeyen isimler korunur ve kalan byte sayısına dahil edilir. Orijinal SAF klasörü, JSON, indeks, queue ve ayarlar silinmez.

Image Cache Size, uygulamanın bu eski türetilmiş dizininde kalan gerçek byte sayısıdır. Sıfır olması bütün uygulama Cache/Data alanının sıfır olduğu anlamına gelmez. WebView Storage ayrı toplamdır; font/HTML/browser verilerini de içerir, saf görsel cache sayacı değildir. Thumbnail Index Size gerçek indeks dosyasının boyutudur. Diagnostics export; files/cache/databases/shared_prefs/app_webview/code_cache ve indeks boyutlarını yalnızca toplam byte olarak verir; cookie veya kişisel dosya adlarını dışa aktarmaz. Otuz saniyelik audit önbelleği sürekli klasör boyutu hesaplamayı önler.

Android Data: koleksiyon belgesi, thumbnail indeksi, ayarlar, sınırlı günlük, SQLite kuyruk ve WAL/SHM, şifreli cookie ayarları, WorkManager verileri ve Chromium özel verilerini kapsayabilir. Cache: WebView kaynakları, kod/cache ve eski türetilmiş dosyalar olabilir. Uygulama APK boyutu bunlardan ayrıdır. Kaynak `.thumbnails` uygulama özel alanına kopyalanmaz; normal SAF kaynağı olarak kalır. Browser'ın orijinal yanıt cache'i ile uygulama tarafından türetilmiş arşiv ayrı olgulardır.

Kullanıcının 766 görsel/5,60 MiB ve Android 11,20 MB Data/614 KB Cache değerleri önceki fiziksel testtir. Bu cihaza erişim yoktur: güncel gerçek kullanıcı klasörü, Android Data/Cache ve gerçek indeks boyutu **Not Measured**. Rapor sonundaki native boyutlar sentetik izole fixture'a aittir.

## Bildirimler ve arka plan

Downloaded = yalnızca SAF commit + readback/doğrulama + indeks güncellemesi sonrası SUCCESS. Existing ayrı; Pending hem yeni bekleyen hem RETRY_LATER kaydını kapsar. Failed ve iki Skipped türü ayrı sayılır. Dar görünüm durum/tamamlanan/toplam/yüzde, geniş görünüm ayrık sayaçlar, gerçek aktif WebView/hesap, oturum ortalama hızı ve mevcutsa ETA içerir. Yeniden deneme girişimleri veya Existing kayıtları tamamlanan hesap hızını şişirmez; bu değer anlık kayan pencere ölçümü olarak sunulmaz. ETA yoksa unavailable yazılır. Sürekli güncelleme en fazla saniyede bir; son durum ayrıca kalıcı sonuç bildirimi olur. Auth, Paused, Interrupted ve Completed ayrılır; bekleyen kayıt varken Completed gösterilmez.

Activity yerine uygulama context'inde tutulan broker, dataSync foreground servis, stopWithTask=false, START_STICKY ve WorkManager kurtarması korunur. Kapanan Activity havuzu yok etmez. Çalışma saatleri, global Retry-After/oturum engeli ve işçi başına bekleme değişmedi. Foreground servis de Android kotalarına tabidir; Android 15 dataSync zaman sınırına mevcut onTimeout yanıt verir. Force Stop/üretici kısıtlarının aşılması garanti edilmez. Resmi kaynak: https://developer.android.com/develop/background-work/services/fgs/timeout

## Test kapsamı ve sınırlamalar

Aynı sentetik kaynak ve trace ile 5.000/10.000/20.000 hesaplık üç tekrarlı önce/sonra host Chromium ölçümü; native aynı provider ile iki geçiş; JSON/sayaç/About/HD regresyonları; özgün kaynak baytları; türetilmiş dosya bulunmaması; eşzamanlı okuma birleştirme ve güvenli eski cache temizliği sınanır. Android 14 emülatörde gerçek WebView 1/2/5/10/30, 25 saniyelik ek ilk dalga, Home, ekran kapama, görev kaldırma ve sonuç bildirimi–doğrulanmış dosya eşitliği sınanır. Yanıtlar offline fixture; canlı Instagram değildir.

Fiziksel telefon FPS/P95/P99, gerçek SAF/SD/cloud gecikmesi, gerçek Instagram başarısı/hızı, gerçek process kill sonrası WorkManager, uzun Doze/üretici pil politikası ve izole decode süresi **Not Measured**. Ağ kesilmesi ve retry/pause/resume politika testleri gerçek mobil bağlantı kesme testi değildir. Daha iyi hız her senaryo için varsayılmaz; sayısal sonuçlar ve olası gerilemeler aşağıda yer alır.


Mevcut kalıcı thumbnail indeksi JSON ve AtomicFile biçimini korur. Tek hesabın metadata girdisi değişse de kalıcı JSON dosyası bütün olarak yazılır; bu görevde farklı bir veritabanına taşınmadı. Çok büyük indekslerde bu yazma maliyeti ayrıca ölçülmelidir. İddia edilen tek-girdi invalidasyonu avatar/browser katmanına aittir.


## Ölçülen sonuçlar

Android/Robolectric: **105 test**, hata/başarısız/atlanan **0**. Chromium UI: **83 kontrol**. Lint: **0 hata**, **23 uyarı**.

### Native kaynak yolu — aynı sentetik test, API 34 Robolectric

| Ölçüm | Önce | Sonra |
|---|---:|---:|
| requests | 64 | 64 |
| sourceOpens | 64 | 64 |
| directoryQueriesDuringReads | 0 | 0 |
| elapsedMs | 117.336645 | 11.374734 |
| responseBytes | 43264 | 38144 |
| originalBytesUnchanged | False | True |
| derivedCacheBytes | 21632 | 0 |
| indexBytes | 6452 | 6452 |
| privateFilesBytes | 6940 | 6940 |
| privateCacheBytes | 21632 | 0 |
| sourceFolderBytes | 20044 | 20044 |

Bu süreler Robolectric/JVM ölçümüdür; fiziksel SAF gecikmesi veya telefon performansı değildir. Önceki diskteki sıcak kopya okumalarının yerine kaynak okumaları gelir; browser reuse bundan ayrı ölçülür.

### Host Chromium — aynı kaydırma izi ve sentetik 256×256 WebP

| Tekrar | Hesap | Sürüm | İlk açılış ms | FPS | Ortalama/P95/P99 frame ms | >32ms oranı | Tepe satır | HTTP görsel istekleri |
|---|---|---|---:|---:|---|---:|---:|---:|
| 1 | 5000 | baseline | 182 | 60.00 | 16.67 / 16.70 / 16.80 | 0.000 | 23 | 616 |
| 1 | 5000 | current | 137 | 60.00 | 16.67 / 16.80 / 16.80 | 0.000 | 24 | 612 |
| 1 | 10000 | baseline | 144 | 59.67 | 16.76 / 16.80 / 16.80 | 0.006 | 23 | 622 |
| 1 | 10000 | current | 156 | 60.00 | 16.67 / 16.70 / 16.80 | 0.000 | 24 | 614 |
| 1 | 20000 | baseline | 169 | 59.67 | 16.76 / 16.80 / 16.80 | 0.006 | 23 | 616 |
| 1 | 20000 | current | 160 | 60.00 | 16.67 / 16.70 / 16.80 | 0.000 | 24 | 614 |
| 2 | 5000 | baseline | 120 | 60.00 | 16.67 / 16.80 / 16.80 | 0.000 | 23 | 616 |
| 2 | 5000 | current | 138 | 60.00 | 16.67 / 16.70 / 16.80 | 0.000 | 24 | 614 |
| 2 | 10000 | baseline | 142 | 60.00 | 16.67 / 16.80 / 16.80 | 0.000 | 23 | 617 |
| 2 | 10000 | current | 137 | 60.00 | 16.67 / 16.70 / 16.80 | 0.000 | 24 | 614 |
| 2 | 20000 | baseline | 142 | 60.00 | 16.67 / 16.70 / 16.80 | 0.000 | 23 | 616 |
| 2 | 20000 | current | 179 | 60.00 | 16.67 / 16.80 / 16.80 | 0.000 | 24 | 614 |
| 3 | 5000 | baseline | 123 | 60.00 | 16.67 / 16.70 / 16.80 | 0.000 | 23 | 616 |
| 3 | 5000 | current | 158 | 60.01 | 16.67 / 16.70 / 16.80 | 0.000 | 24 | 614 |
| 3 | 10000 | baseline | 126 | 60.00 | 16.67 / 16.70 / 16.80 | 0.000 | 23 | 616 |
| 3 | 10000 | current | 140 | 60.00 | 16.67 / 16.80 / 16.80 | 0.000 | 24 | 614 |
| 3 | 20000 | baseline | 139 | 58.37 | 17.13 / 16.80 / 33.40 | 0.017 | 23 | 616 |
| 3 | 20000 | current | 165 | 60.00 | 16.67 / 16.80 / 16.80 | 0.000 | 24 | 614 |

Host Chromium FPS, Android ekran FPS'si değildir. JS heap ölçümü ayrı JSON'da vardır; PSS değildir. Native cache hit yerine eşzamanlı okuma birleştirme, JS browser Image reuse hit/miss ayrı tutulur. Browser'ın dahili decode cache hit oranı ve izole decode süresi: **Not Measured**.

### Arka plan ve bildirim

Android 14 gerçek Chromium/offline fixture: Home 0 → 2; ekran kapalı 2 → 4; görev kaldırma sonrası 8/8. Doğrulanmış kaynak dosyalar 8; Downloaded 8; sonuç bildirimi progress 8.

### Paket

Debug APK: 2907865 bayt. Optimize imzasız release: 683994 bayt. Kod commit: `832f7d34205a8270fe289914548eeff12259388b`. Kaynak Fetcher: `b3f03f2a30c36f89add5e954b9b121a7b9d899ae`.


## Değiştirilen dosyalar

- `.github/workflows/android.yml`
- `.github/workflows/package-verified.yml`
- `.gitignore`
- `README.md`
- `app/src/androidTest/java/com/instagram/insfav/fetcher/PoolInstrumentation.java`
- `app/src/main/assets/app.js`
- `app/src/main/assets/core.js`
- `app/src/main/java/com/instagram/insfav/LocalImages.java`
- `app/src/main/java/com/instagram/insfav/MainActivity.java`
- `app/src/main/java/com/instagram/insfav/fetcher/DownloadNotification.java`
- `app/src/main/java/com/instagram/insfav/fetcher/DownloadWorker.java`
- `app/src/main/java/com/instagram/insfav/fetcher/FetchService.java`
- `app/src/main/java/com/instagram/insfav/fetcher/QueueEngine.java`
- `app/src/test/java/com/instagram/insfav/LocalImagesTest.java`
- `app/src/test/java/com/instagram/insfav/ThumbnailPipelineBenchmarkTest.java`
- `app/src/test/java/com/instagram/insfav/fetcher/NotificationProgressTest.java`
- `docs/THUMBNAIL-OPTIMIZATION-TR.md`
- `tests/browser.cjs`
- `tests/core.test.cjs`
- `tests/thumbnail-performance.cjs`
- `tools/delivery-report.py`
- `tools/emulator-pool.sh`
- `tools/web-bundle.json`
- `version.properties`
- `web/app.js`
- `web/core.js`
- `web/maps/app.js.map`
- `web/maps/core.js.map`

## Üç tekrarın medyanları

| Hesap | Önce/sonra FPS | Önce/sonra ilk açılış ms | Önce/sonra tepe satır | Önce/sonra HTTP istekleri |
|---|---|---|---|---|
| 5000 | 60.00 / 60.00 | 123.00 / 138.00 | 23.00 / 24.00 | 616.00 / 614.00 |
| 10000 | 60.00 / 60.00 | 142.00 / 140.00 | 23.00 / 24.00 | 617.00 / 614.00 |
| 20000 | 59.67 / 60.00 | 142.00 / 165.00 | 23.00 / 24.00 | 616.00 / 614.00 |

FPS'de genel bir artış kanıtlanmadı. İlk açılış bazı örneklerde daha yavaştı; DOM ve HTTP istekleri her satırda azalmış gibi sunulmaz. Kesin doğrulanan değişiklikler; kaynak baytlarının korunması, liste yeniden kodlamasının kaldırılması ve türetilmiş disk cache'in sıfırlanmasıdır.

## Gerçek Android WebView havuzu — offline fixture

| Requested | Effective | Peak active WebViews | Peak accounts | Peak CDN | Accounts/min | App PSS KiB |
|---|---|---|---|---|---|---|
| 1 | 1 | 1 | 1 | 1 | 6.15 | 66676 |
| 2 | 2 | 2 | 2 | 2 | 12.42 | 70333 |
| 5 | 5 | 5 | 5 | 5 | 30.09 | 73441 |
| 10 | 10 | 10 | 10 | 10 | 36.13 | 78194 |
| 30 | 15 | 15 | 15 | 15 | 52.56 | 79355 |
| 5 | 5 | 5 | 5 | 5 | 29.98 | 77373 |

Son 5 işçi satırı 25 saniyelik bekleme ayarında ilk dalgayı ölçer; uzun dönem hız değildir. PSS yalnız ana uygulama sürecinin işlem sonu ölçümüdür; ayrı Chromium renderer toplamını veya liste RAM karşılaştırmasını göstermez. Farklı profillerin gezinme ve CDN okuma aralıkları ham JSON'da çakışır.

## Paketleme ve imza sınırlaması

Uygulama testleri ve Android 14 emülatör senaryoları 39526391363 numaralı çalışmada geçti. O çalışmanın son paketleme adımı sığ Git geçmişinde eski karşılaştırma commit'ini bulamadı. Tam geçmişle yalnız paketleme yeniden çalıştırıldı; derlenmiş uygulama kodunun test edilen commit ile aynı olduğu git diff guard ile kontrol edildi.

CI debug sertifikası önce teslim edilen 3.3.0 CI debug sertifikasından farklıdır; dolayısıyla o APK üzerine doğrudan güncelleme yapılamaz. Mevcut uygulamayı silme veya veri temizleme önerilmez. Veri ve sayaçları koruyan yerinde güncelleme aynı eski imzalama anahtarını gerektirir. Eski CI anahtarı elde değildir. Yeni APK temiz kurulum için çalışır; imza karşılaştırması kamuya açık sertifika SHA-256 değerleriyle doğrulanmıştır.


Teslimat: https://github.com/BlackWare-AI/INSFAV/actions/runs/39527991317/artifacts/12312829221

Uygulama testleri: https://github.com/BlackWare-AI/INSFAV/actions/runs/39526391363

Paketleme doğrulaması: https://github.com/BlackWare-AI/INSFAV/actions/runs/39527991317
