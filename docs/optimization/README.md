# INSFAV-Plus: davranışı koruyan optimizasyon incelemesi

2026-10-27 birleştirme güncellemesi: `main` üzerindeki `026a631` (#25) pano yapıştırma ve bağımsız HD düzeltmeleri bu dala alındı. Pano komutunun ana iş parçacığında çalışması ve mevcut status görevlerini birleştirme birlikte korundu. Aşağıdaki ölçümler ve test sayıları belirtilen ilk teslim commit'lerine aittir; yeni birleşik kaynağın doğrulama sonuçları [PR #24 kontrollerinde](https://github.com/BlackWare-AI/INSFAV-Plus/pull/24/checks) izlenir.

Başlangıç: `main` / `70911576b65e04de45dd6d98a7fed9a0d4820089`. Her uygulama kendi başlangıcıyla, ayrı `perf/preserve-behavior-optimization` dalında karşılaştırıldı. Native ölçüm kaynağı: `1f43a9c8432cd93edf05634d24e638bad0b18274`; tam içerik parmak izi ham JSON'dadır. Bu ölçümden sonraki değişiklikler row-pool kaynak bırakma düzeltmesi ve çalışma sırasında main'e gelen oturum doğrulama düzeltmelerinin birleştirilmesidir. Ölçülen native optimizasyon yolları korunur; sayılar belirtilen ölçüm commit'ine aittir. Son birleşik sürüm ayrıca bütün testlerden geçirilir.

AGENTS/CONTRIBUTING incelendi. Plus'ın kendi repo sınırı bu iki repoyu açıkça isteyen görev kapsamında uygulandı; Plus kullanıcı özellikleri INSFAV'a eklenmedi. Uygulama kimlikleri, main'deki mevcut sürümler, release imzalama ayarları, JSON export/import, dosya adları, şifreleme, kaynak görsel boyutu/kalitesi ve indirme politikaları değiştirilmedi. Release yayımlanmadı. CSS, renk/font/ölçü/animasyon ve yerleşim kaynakları değiştirilmedi.

## Yapılanlar

- Envanterde aynı kapsam/indeks/koleksiyon/sorgu/tarama nesli için sayfalar tek sonuç sırasını kullanır. Yalnız son sorgu, en fazla 20.000 hafif kayıt tutulur; daha büyük sonuçlarda eski tam tarama yolu sürer. Her sayfa yeni geçici preview token'ları üretir. Eksik/tamamlanmamış tarama sonuçları saklanmaz; başarısız/iptal edilen tarama da nesli değiştirir.
- Mevcut kalıcı günlük, profil fark aktarımı, olay tabanlı indirme ve parçalı görsel okuma yeniden yazılmadı. Kaynak byte/ölçü/kalite sınırları ve Plus'a özel ekranlar korunur.

Ortak değişiklikler:

- Küçük durum okumalarında matched/missing özeti indeks nesli, koleksiyon nesli ve kullanılabilirlik değişimine göre tekrar kullanılır; tam profil üretimi yalnız gerektiğinde yapılır. Eşit public revision ile indeks değiştirme de geçersizleştirir.
- `StatusTasks` yalnız bekleyen status/fetcher-status okumalarını birleştirir. Kullanıcı komutları aynı seri executor'da tek tek kalır; kritik pause/stop/error/completion bildirimleri bu birleştirmeyi ve normal limiter'ı geçer.
- Küçük JS yaması tüm profil haritasını kopyalamaz; yalnız değişen ve görünür avatarlar yenilenir. `state` eski ham JSON/profil yanıtlarını ayrıca tutmaz. Değişmeyen görünür metin/metric node'ları tekrar yazılmaz.
- `FetcherCatalog.seed()` eski görsel satırlarını transaction içinde topluca okur. Kullanıcı başına SELECT ve ikinci silinmiş-kayıt taraması kalkar; boş URI, needs_check, kayıp kayıtları tekrar kuyruğa alma ve transaction sınırları korunur.
- Fetcher status okuması çözünürlük hesabı için aynı QueueStore helper'ını kullanır. İndeks günlüğü helper'ı tek dirty grup boyunca yaşar; flush/ack veya record hatasında kapanır. Süreç boyunca RAM-only kalıcılığa geçilmedi.
- Değişmeyen image-settings JSON'u yeniden atomik yazılmaz. Günlük bağlantısı `synchronous=FULL` kullanır ve bu ayar test edilir; cihazın daha gevşek WAL varsayımına güvenilmez. Gerekli flush/commit'ler korunur; ayar Kaydet akışının pacing yan etkisi değiştirilmez.
- Avatar önceliği sanal satır koordinatlarını kullanır. Zoom/pan aynı sınırlarla frame başına tek geometri okuma/transform yazımına birleşir; kapanmış/gizli viewer işi iptal edilir. Stream kaydı/kapanış yarışı da kapatılır.
- Ağır görsel doğrulaması Store kilidi dışına çıkarıldı. Ağaç/dizin ve indeks kimliği yeniden kontrol edilmeden sonuç yayımlanmaz; indeks değişimi sırasında gelen geç sonuç testi eklendi.
- `MatchedSummary`, `StatusTasks`, encoded-source helper ve `web/avatar-loader.js` sorumlulukları ayrıldı. Web parçaları aynı app.js içine sıralı birleştirilir; ek HTTP isteği/dependency yoktur. Gradle `verifyWebAssets`, ana/ek web kaynaklarının ve APK kaynak bundle'larının hash'lerini paketleme öncesinde doğrular.
- Sık kullanılan identity/filename regexleri ayrı kontrol seviyeleri korunarak derlendi. Yeni inceleme yollarındaki önemli boş catch'ler sabit tanılama kodlarıyla raporlanır; hesap/URI/çerez/parola/anahtar yazılmaz.

## Zaten mevcut olduğu için yeniden uygulanmayanlar

Plus'ta zaten mevcut ve korunan: günlükten replay ve watermark/AtomicFile kurtarma; 64/60 toplu snapshot; event/deadline broker; ilk yükleme + profil farkları; kritik bildirimlerin limiter dışına çıkması; kaynak gözlemi; parçalı ve paylaşılan encoded okuma; görünür metrik node'larının tekrar kullanımı; Türkçe display-name katlamasının sorgu başına hesaplanması.

İki uygulamada zaten mevcut ve korunan: read-only SAF taraması, iki doğrulama çalışanı, 256 kayıt grubu, değişmeyen dosya metadata/checkpoint tekrar kullanımı, sanal liste/matris ve sınırlı DOM, 32 account-row ve 64 browser-image sınırı, en fazla altı browser görsel yüklemesi, görüntü doğrulama ve atomik kaynak yazma kuralları. INSFAV'ın mevcut byte-future istek paylaşımı yeni bir kazanım gibi sunulmaz.

## Host ölçümleri

JDK 17, SDK/build-tools 35, Gradle 8.13, Robolectric API 28/34; 2 CPU / 8 GiB host. Native ana karşılaştırmalar üç turdur; ikinci tur ters sıra kullanır, tablolar medyandır. Coverage agent iki tarafta da kapalıdır. Ağ profili/Instagram isteği çalıştırılmaz. Soğuk tarama boş checkpoint, sıcak tarama değişmeyen checkpoint anlamındadır; kernel page-cache veya soğuk process ölçümü değildir. Ham sonuç: [native.json](native.json).

| İş yükü | Önce | Sonra |
|---|---:|---:|
| 15.000 profilde 64 kayıt: geçen süre | 279.25 ms | 126.85 ms |
| Aynı iş: tam snapshot sayısı | 1 | 1 |
| Aynı iş: snapshot içerik baytları | 2,421,238 | 2,421,238 |
| Aynı iş: süreç wchar (SQLite dahil) | 3,615,158 | 3,067,862 |
| Aynı iş: süreç yazma çağrısı | 1,021 | 313 |
| 20 değişmeyen devam yanıtı: süre | 105.18 ms | 3.28 ms |
| 20 değişmeyen devam yanıtı: bayt | 18,720 | 19,120 |
| Bir profil değişince yanıt baytları | 1,063 | 1,083 |
| 5.000 profillik değişmeyen katalog eşitlemesi | 395.49 ms | 103.61 ms |
| Yaklaşık 1 saniye boş dispatcher bekleme geçişi | 0 | 0 |

Linux `write_bytes` her iki tarafta da 0'dır. `wchar` gönderilen içeriği ölçer; NAND yazımı, flash ömrü veya pil ölçümü değildir. Journal her başarı için kalıcı küçük yazıyı korur. INSFAV'da toplam yazma çağrısı artarken içerik miktarı düşer. Plus'ın public snapshot sayısı zaten 1 idi. Son durumda 64 kayıt + ack tek journal helper oturumu kullanır; eski Plus yolunda 64 record ve bir ack için ayrı helper açılırdı. Ack/replay SQL mantığı ölçülmüş bir fayda olmadan değiştirilmedi.

Katalogda başlangıç kullanıcı başına 5.000 SELECT kod yoluna sahiptir; yeni sayaç bu SELECT'leri 0 gösterir, bir bulk SELECT kalır. Ham baseline `pointReads=-1`, başlangıçta enstrümantasyon bulunmadığını belirtir; bu değer bir ölçüm gibi sunulmaz.

100 küçük `snapshot(false)` yanıtı; her tur içindeki beş örneğin medyanı, sonra üç turun medyanı:

| Hesap | Önce | Sonra |
|---|---:|---:|
| 5,000 | 41.54 ms | 1.00 ms |
| 15,000 | 82.14 ms | 0.70 ms |
| 20,000 | 67.04 ms | 0.43 ms |

Metadata Store yeniden açılması (mevcut JSON + varsa günlük kurtarma). Bu ölçümde son sürüm daha yüksek maliyet gösterebilir: kalıcı günlüğü kontrol etme ve güvenli kurtarma maliyeti korunmuştur. Bu, uygulamanın soğuk açılışı veya splash süresi değildir; açılış iyileşmesi iddia edilmez:

| Profil | Önce | Sonra |
|---|---:|---:|
| 5,000 | 17.68 ms | 23.38 ms |
| 15,000 | 20.34 ms | 39.69 ms |
| 20,000 | 22.41 ms | 52.12 ms |

Gerçek sentetik 128/256 WebP dosyaları, her SAF open için 1 ms simülasyon. Aşağıdaki değerler soğuk/sıcak ms'dir. 15.000 için üç çift, sınır boyutları için bir çift: [scale.json](scale.json). Kaynak byte/tarih fingerprint'i eşit, kaynak yazımı 0, sıcak source open sayısı 0, doğrulama paralelliği 2 olarak doğrulandı.

| Dosya | Önce soğuk / sıcak | Sonra soğuk / sıcak | Örnek |
|---|---:|---:|---|
| 5,000 | 5075 / 226 | 4894 / 217 | 1 çift |
| 15,000 | 13045 / 495 | 12873 / 440 | 3 çift |
| 20,000 | 15503 / 335 | 15563 / 358 | 1 çift |

Tarama, açılış veya pil için bu küçük/karışık host farklarından genel hız kazanımı çıkarılmaz. Decode, SQL ve checkpoint sayaçları sınır taramalarının coldIO/warmIO nesnelerinde korunur.

## Envanter

Üç eşleştirilmiş tur; 100 kayıtlık bütün sayfalar sırayla okunur. Sonuç sırası/toplam ve salt okunurluk her sayfada doğrulanır. Başlangıçtaki tam tarama sayısı kod yolundan, sonuçtaki bir geçiş native sayaçtan gelir.

| Kayıt | Önce | Sonra | Katalog geçişi |
|---|---:|---:|---:|
| 5,000 | 181.98 ms | 82.42 ms | 50 → 1 |
| 15,000 | 519.88 ms | 152.77 ms | 150 → 1 |
| 20,000 | 641.49 ms | 152.95 ms | 200 → 1 |

Önbellek hafif immutable alanlar taşır; tam profil JSONObject kopyası tutmaz. Sınırlı bir RAM/CPU takasıdır; toplam Android PSS/peak RAM düşüşü iddia edilmez.

## Arama

Node 22, üç eşleştirilmiş tur medyanı; aynı sorgu 20 kez. Yalnız tekrar sorgu yolu ölçülür; ilk sorgu, bütün farklı sorgular veya cihazdaki kullanıcı gecikmesi için genel hız iddiası değildir. Parse/liste süreleri de [search.json](search.json) içinde saklanır. Mevcut debounce ve uygulamaya özgü eşleşme kuralları korunur.

| Hesap | Önce ms | Sonra ms |
|---|---:|---:|
| 5,000 | 4.01 | 0.00 |
| 15,000 | 12.49 | 0.00 |
| 20,000 | 17.64 | 0.00 |

## Arayüz ve görünüm

Son görünüm karşılaştırmasının başlangıcı çalışma sırasında güncellenen main'dir: INSFAV `9af95d3`, Plus `dfdd035`. Oturum düzeltmeleri iki tarafta da bulunur. Native performans tablolarının ilk başlangıç referansları yukarıda ayrıca belirtilmiştir.

393×820, ölçek 1, aynı sentetik veri ve sabit locale/saat dilimi. Her boyutta kapalı/açık liste, kaydırılmış liste, arama, 2/3/4/5 matris, About, ayarlar, indirme ekranı ve viewer karşılaştırılır. 36/36 karşılaştırma kabul edildi; 36 PNG byte olarak aynıdır. Anti-alias sınırı en fazla 128 pikselde kanal başına 2 seviyedir; tam fark sayıları, hash'ler ve PNG çiftleri [visual-and-render.json](visual-and-render.json) ve [screenshots/](screenshots/) içindedir. CSS kaynakları değişmez.

Aynı Chromium oturumunda 90 frame kaydırma; render p95 / frame aralığı p95 ms. Her boyutta tek izdir; Android jank sayacı veya doğrulanmış kaydırma hız artışı değildir. Görseller kontrollü SVG fixture'larıdır; gerçek WebP decode maliyeti native tarama testindedir.

| Hesap | Render p95 önce / sonra | Frame aralığı p95 önce / sonra | >25 ms aralık önce / sonra |
|---|---:|---:|---:|
| 5,000 | 1.50 / 0.80 | 16.80 / 16.70 | 0 / 0 |
| 15,000 | 1.20 / 0.80 | 16.70 / 16.70 | 0 / 0 |
| 20,000 | 1.50 / 0.80 | 16.70 / 16.70 | 0 / 0 |

Aktif browser görselleri ≤6, tutulanlar ≤64, sanal satırlar sınırlı kalır. Avatar başına getBoundingClientRect çağrısı yeni izde 0'dır; INSFAV'ın matris ölçüm probe'u korunur. 100 transform girdisi en fazla bir style yazımı üretir. Profil map kimliği, değişmeyen avatar kimliği ve kullanılmayan state payload'larının bırakılması ayrıca doğrulanır. Emekliye ayrılan account row'larının avatar/link event handler'ları ve uzun basma timer'ı temizlenir; eski kayıt referansları bırakılır. Chromium WeakRef + zorlanmış GC kontrolünde eski koleksiyon başlangıçta tutulurken son durumda üç boyutta da bırakılır. Havuzdan çıkan satırın yeni kullanıcıyı açtığı ve eski görsel taşımadığı doğrulanır; bu Android PSS ölçümü değildir.

## Kontroller ve kabul kapsamı

- Başlangıç native testleri: 319; son native testleri: 334. Hata/skip 0. `npm test`, lintDebug, assembleDebug, assembleDebugAndroidTest ve kaynak/bundle kontrolü: [final-checks.json](final-checks.json). Ek kaynak veya paketlenmiş app.js kasıtlı bozulduğunda derlemenin durduğu, geri yüklemede geçtiği ve debug APK hash'lerinin manifest ile eşleştiği [asset-checks.json](asset-checks.json) içinde kayıtlıdır.
- Lint hatası 0; başlangıç ve son uyarı sayıları ayrı saklanır (ana dal birleştirmesi sonrası sayılar da buna dahildir). [baseline-checks.json](baseline-checks.json) ile karşılaştırılır. Güvenlik için gereken commit/flush, lint tavsiyesi nedeniyle async'e çevrilmedi.
- Gerçek tarayıcı script sonuçları [browser-checks.json](browser-checks.json). Plus'ın mevcut scroll testindeki modal kapanışı yarışı baseline'da da üretildi; test artık kapanış animasyonunun gerçekten bitmesini bekler. Üretim animasyonu ve 249/250 ms sınırları değiştirilmedi.
- JSON unknown alanları/orijinal export, tekrar eden üyelik ve sıra; arama ve bütün mevcut matrisler; HD/local görüntü; indirme/retry/pause/stop/resume; foreground/background; folder/permission ve source observer; bozuk/eksik/büyük görseller mevcut testlerle kontrol edildi.
- Disk yazımı başarısız olsa bile bellekte değişmiş indeks için özet ve profil farkı geçmişi geçersizleştirilir. Günlük önce-bellek sonra, 63 kaydın eşik altında kesintisi, AtomicFile yarım yazımı, snapshot sonrası ack kesintisi, replacement ve ikinci restart idempotence testleri geçti. Güç kesme donanımı simüle edilmedi. `synchronous=FULL` journal bağlantısında test edilir; fiziksel depolamada bunun I/O maliyeti ayrıca ölçülmelidir.
- Yeni testler: özeti yeniden kullanma ve import/clear/eşit-revision replacement/erişim geçersizleştirmesi; journal helper yaşam döngüsü/hata; komutların birleştirmeden geçmesi; Store kilidi dışında doğrulama ve geç yayımlama reddi; Plus envanter sayfa/query/indeks/koleksiyon/erişim geçersizleştirmesi.

## Geri alınan ve sınırlandırılan denemeler

Durum/faz/held sayımlarını tek GROUP BY'a birleştirme 20.000 hesapta yaklaşık **137,89 → 226,29 ms** (20 okuma, medyan) ile yavaşladı. Plan ek geçici B-tree kullanıyordu; geri alındı. [rejected-query.json](rejected-query.json) gerçek planları içerir. Mevcut covering index'ler korundu; ölçümsüz indeks eklenmedi.

Kalıcı/çok-helper kuyruk toplam önbelleği eklenmedi: transaction commit/rollback ve başka helper'ların yazıları için yeni invalidation protokolü getirilmedi. Bekleyen tekrar status okumaları birleştirilir, tek okuma kapsamındaki helper paylaşılır; SQL toplamları gerektiğinde mevcut indekslerle canlı okunur.

Matris hücre havuzu, klasör satırlarını kısmi splice ile yeniden kurma ve başlangıç bağımlılıklarını async taşıma üretime alınmadı. Mevcut DOM/row havuzu ve kaynak-bırakma kontrolleri ölçüldü; bu ek değişikliklerin cihazda faydası kanıtlanmadı. Ana iş parçacığındaki bootstrap/index replay akışı korunur; mevcut splash/recovery ekranları yeniden yazılmaz. Bu maddeler için hız/RAM kazanımı iddia edilmez.

## Ölçülemeyenler

Fiziksel cihaz ve emulator bağlı değildi. Toplam Android süreç/renderer/GPU PSS veya peak RAM, pil/CPU enerjisi, gerçek soğuk process açılışı, yavaş gerçek SAF sağlayıcısı, canlı servis ve fiziksel güç kesintisi ölçülmedi. JS heap veya host JVM değerleri bunların yerine kullanılmadı. Donanım kabulü yapılmadan bu alanlar tamamlanmış sayılmaz.

## Tekrarlama

```sh
npm ci --ignore-scripts
npm run web
npm test
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/benchmark-optimization.py --baseline-ref 70911576b65e04de45dd6d98a7fed9a0d4820089 --rounds 3
python3 tools/benchmark-optimization.py --baseline-ref 70911576b65e04de45dd6d98a7fed9a0d4820089 --rounds 1 --scale-scans-only --output artifacts/optimization-scale
# BASELINE_ASSETS bu uygulamanın yukarıdaki commit'inden ayrı worktree assets yoludur.
BASELINE_ASSETS=/path/to/baseline/app/src/main/assets node tests/optimization-browser.cjs
```

Playwright mevcut Chromium test altyapısı üzerinden kullanılır; INSFAV'ın CI'sindeki gibi kurulu Playwright veya NODE_PATH gerekir. Yeni runtime dependency eklenmedi. Plus envanter ölçümü için ek olarak `--inventory-only --output artifacts/optimization-inventory` kullanılır. Son iki komut yalnız host fixture'ları çalıştırır; release üretmez/yayımlamaz.
