# INSFAV 5.0.0 / Build 10 — Teknik çalışma raporu

## 1. Bu raporun kapsamı ve kanıt dili

Bu belge INSFAV Android uygulamasının son arayüz ve denetim güncellemesini, mevcut çalışma mimarisini, veri biçimlerini, güvenlik sınırlarını ve test kanıtlarını açıklar. Belgenin en altındaki kaynak eki, belirtilen uygulama commit'inin tüm metin kaynaklarını dosya dosya içerir. Gömülü ZIP; kaynak, Gradle projesi, testler ve font/görsel gibi ikili proje varlıklarını da taşır. Çalışan cihazın koleksiyonları, cookie değerleri ve imza özel anahtarı raporun parçası değildir.

CODE_REVIEW kaynakta görülen davranış; UNIT_TEST JVM/Robolectric veya Chromium fixture kontrolü; DEVICE_TEST gerçek Android emülatöründeki framework, Binder ve WebView kontrolüdür. Bu çalışmada fiziksel kullanıcının telefonu ve canlı Instagram oturumu test edilmedi. Fixture, kapalı bir gerçek hesabın doğrulaması olarak sunulmaz. Rapor başındaki makine kanıtı son test/yayın durumunu ayrıca gösterir.

Uygulama adı INSFAV, paket adı com.instagram.insfav, görünen sürüm 5.0.0 ve versionCode/build 10'dur. Kullanıcının talebi doğrultusunda bu iki sürüm değeri değiştirilmedi. Aynı versionCode ile yeni APK içeriği üretildiğinden iki APK'yı ayırmak için kaynak commit'i ve SHA-256 özeti kullanılır. Önceki build 10 APK'sının ve yeni APK'nın sertifikası karşılaştırılır; yeni anahtar üretilmez.

## 2. Bu güncellemede değişenler

Unreadable Images ve Duplicate Image Candidates bilgi kartları Settings görünümünden kaldırıldı. Dosyaların okunabilirlik kontrolü ve doğru duplicate adayının seçimi çalışmaya devam eder. Kaldırılan, bilgi kutularının gösterimidir; veri bütünlüğünü sağlayan kontrol değildir.

Invalid Resolution Images detay modalı ve bu sayacı tıklayınca açılan liste kaldırıldı. Sayısal bilgi kartı korunur. Bu kart artık yalnızca 256×256 hedefini ölçer: 128×128, daha büyük, daha küçük ve dikdörtgen görseller sayılır. Görüntüleme ve mevcut-thumbnail uyumluluğu değişmedi; 128×128 dosya kullanılmaya devam eder. Sayacın amacı dosyayı değiştirmek veya yeniden indirmek değil, istenen boyuttan farklı dosyaları bildirmektir.

About / Application information içindeki Application Name satırı kaldırıldı. Developer, AI Development Assistance, Version, Build, toplam hesap, kullanım/yükleme/sıfırlama sayaçları, Add List, Delete List, Features & Functions ve açıklama metni korunur. Settings ve Close düğmeleri aynı satırda iki eşit kolon oldu. Modal yüksekliği içerik ve ekran sınırıyla hesaplanır; ayrı sabit yükseklik eklenmedi.

Missing Images kartının sağ üstünde 14×14 px kopyala glifi ve 28×28 px etkileşim alanı bulunur. Glif, sayı fontundan büyük değildir. Düğme eksik kullanıcı adlarını native tarafta yetkili katalogdan toplar ve her satıra bir ad yazar. Klasör, profil URL'si, görünen kişi adı ve yinelenen hesap yazılmaz. Boş listede düğme pasiftir; panonun mevcut içeriği silinmez. Yazma hatasında başarı mesajı gösterilmez.

Last Scan Date ve Selected Folder iki kolonlu aynı satırdadır. Mevcut 12 px bilgi fontu korunur. Klasör metni gerektiğinde sağdan üç noktayla kısalır; tam metin DOM ve title içinde kalır. Diğer metriklerin sayı ve etiket fontları korunur.

Modal arka planında tek, sabit 4 px backdrop blur uygulanır. Açma/kapatma ve ekran geçişi 160 ms opacity fade kullanır. Blur yarıçapı animasyonu ve her frame çalışan JavaScript döngüsü eklenmedi. Reduced motion tercihinde animasyon kapatılır. GPU'da sıfır maliyet garantisi verilmez; fiziksel cihaz GPU ölçümü bu çalışmada yoktur.

Fetcher bildirimi kısa durum ve Downloaded sayısını gösterir. Genişletilmiş görünümde kalan iş, mevcut dosyalar ve atlanan/başarısız sayıları bulunur; WebView, hız ve ETA satırları kaldırıldı. Manuel Retry bekleyen sonuçlar aktif kalan iş sayılmaz. Kuyruk yalnızca bu kayıtlara kaldığında bildirim Waiting durumunda takılmak yerine Completed — Retry available durumuyla kapanır. Gerçek rate-limit, oturum ve fatal hata açıklamaları korunur.

## 3. Android kabuğu ve yerel web arayüzü

MainActivity bir Android Activity ve tek yerel WebView barındırır. Arayüz app/src/main/assets içinden https://insfav.local sanal origin'iyle sunulur. Bu origin gerçek bir sunucu değildir; WebViewClient dosya ve image isteklerini uygulama içinden yanıtlar. Kullanıcı arayüzünün dosya/content erişimi kapalıdır; mixed content yasaktır. Kaynaklar yalnızca izin verilen asset dosya adı desenleriyle okunur. Uzak profil açma Android Intent üzerinden yapılır; yerel arayüzün navigasyonu dış sayfaya bırakılmaz.

JavaScript ile Android arasında Android.request(JSON) ve window.nativeEvent(event,data) köprüsü vardır. Native tarafta genel I/O için tek executor, Rescan için ayrı tek executor kullanılır. WebView ve clipboard işlemleri ana thread'e post edilir. Uzun tarama ana thread'de çalışmaz. Yeni copy-missing-users komutu kullanıcı adı listesini JavaScript'ten kabul etmez; Store ve LocalImages kataloğundan üretir.

Web kaynaklarının düzenlenebilir halleri web/ altındadır. tools/minify.cjs, terser ve clean-css ile app/src/main/assets paketlerini, web/maps source map dosyalarını ve tools/web-bundle.json hash manifestini üretir. CI aynı üretimi yeniden çalıştırır ve tracked çıktılarda fark oluşursa başarısız olur. Bu değişiklikte yeni runtime veya npm bağımlılığı eklenmedi.

## 4. Koleksiyon JSON'u, kimlik ve sayaçlar

Store; kök JSON dizisini, favorite_page_pref dizisini veya JSON metni olarak taşınan favorite_page_pref dizisini kabul eder. Koleksiyonun name alanı metin, items alanı dizi, hesabın pageId alanı metin olmalıdır. name opsiyoneldir. Import en fazla 32 MiB okur. Fetcher veya tarama çalışırken koleksiyon import'u durdurulur. Orijinal JSON document.json içinde tutulur; Export Original JSON bu metni geri verir. Kaynak koleksiyon alanları tarama sırasında yeniden yazılmaz.

Kullanıcı adları trim, baştaki @ işaretini kaldırma ve küçük harfe çevirme ile normalize edilir. Store ve arayüz üyelik sayısını ve benzersiz hesap sayısını ayırır. Aynı hesap birden fazla klasörde görünür; Fetcher'ın SQLite hesabı normalize kullanıcı adını primary key olarak tutarak bir kez işler. Native Fetcher kimlik doğrulayıcısı uzunluk, karakter ve nokta kurallarını ayrıca uygular.

Usage, Load ve Reset sayaçları Activity tercihleriyle kalır. About ekranını tekrar açmak kullanım veya yükleme artırmaz. Başarılı import yükleme sayısını bir kez, Clear Collections sıfırlama sayısını bir kez artırır. Arayüzdeki kaldırılan Application Name satırı bu veri akışını değiştirmez. Var olan counter regresyon testleri aynen korunmuştur.

## 5. Kalıcı dosyalar ve veritabanları

| Alan | Amaç ve neden var |
|---|---|
| document.json | Orijinal koleksiyon metni; image indeksinden bağımsız tutulur. |
| image-settings.json | Seçilen SAF tree, directoryId, klasör etiketi ve tarama durumu. |
| thumbnail-index.json | Schema 1 public image snapshot; seçilmiş profil adayları, metadata, revision ve scannedAt. Yeni resolutionAudit alanı isteğe bağlı ek alandır. |
| thumbnail-scan.db | SQLite schema 1; tüm dosya adayları ve 256 satırlık tamamlanmış kontrol noktaları. WAL kullanır. |
| fetcher-queue.db | SQLite schema 5; accounts, fetch_images, fetch_meta ve fetch_outcomes tabloları. WAL kullanır. |
| fetcher-cookies.enc | AES-GCM ile şifreli cookie deposu; anahtar Android Keystore'dadır. |
| fetcher-write-journal.json | Kesilmiş SAF yazma/rename işleminden güvenli toparlanma kaydı. |
| fetcher SharedPreferences | Worker, schedule, pacing, cooldown, arm/pause, session evidence ve çalışma durumu. |
| diagnostics.txt / fetcher-diagnostics.txt | Sınırlı/rotate edilen sabit olay kodları ve sayısal tanılama. |

Store, JSON ayar ve indeks değişiminde Android AtomicFile kullanır. Public image snapshot yalnızca tamamlanmış taramadan sonra değiştirilir. Özel checkpoint tablosu, kesinti olsa da bitmiş batch'leri korur. Fetcher kuyruğu ile normal thumbnail indeksi ayrı sorumluluklardır; birini temizlemek kaynak dosyaları silme anlamına gelmez.

## 6. SAF klasörü nasıl kullanılır?

Android'in ACTION_OPEN_DOCUMENT_TREE akışı kalıcı okuma izni alır. Seçilen klasörün adı .thumbnails değilse doğrudan .thumbnails çocuğu bulunabilir; bazı Android seçicileri noktayla başlayan klasörü gizlediği için bu destek vardır. Yetki tree URI ile, gerçek alt klasör directoryId ile belirlenir. Klasör adı tek başına izin kanıtı değildir.

Normal görüntüleme ve Rescan okuma izniyle çalışır. Fetcher yazmak için aynı seçilen tree üzerinde ayrıca okuma+yazma izni ister. Kullanıcı ikinci/farklı klasör seçerse mevcut konum korunur ve istek reddedilir. Geniş depolama erişim izni kullanılmaz. İzin kaybında kayıtlar ve dosyalar silinmez; kullanıcıdan klasörü yeniden yetkilendirmesi istenir.

## 7. Rescan: ilk indeks ve sonraki taramalar

ThumbnailScanner, DocumentsContract child URI üzerinde tek ContentResolver.query ile document ID, display name, size, modified ve MIME type sütunlarını alır. DocumentFile üzerinden her dosya için ek metadata sorguları yoktur. Klasör erişim kontrolü ayrı tek metadata sorgusudur. Bu nedenle benchmark'taki iki toplam SAF query, bir kontrol + bir bulk çocuk listesidir.

Desteklenen görseller WebP, JPEG/JPG ve PNG'dir. ImageCodec, bounds okuması ve 128 px'e örneklenmiş pixel decode ile dosyanın okunabilirliğini sınar. Normal küçük thumbnail'da 64 KiB sınırlı rewind buffer aynı stream'i yeniden kullanır; genellikle tek kaynak açılışı yeter. Çok büyük JPEG header'ında mevcut iki-açılış fallback korunur. Boyut sınırları 64 MiB stream, en fazla 32768×32768 kenar ve 100 milyon pikseldir. Salt metadata başlığı geçerli diye bozuk dosya başarılı kabul edilmez.

İki validation worker ve 256 dosyalık batch kullanılır. SQLite yazımları prepared statement ve transaction ile yapılır. Progress en fazla yaklaşık 250 ms aralıkla yayımlanır. Listede değişmediği güvenle kanıtlanan dosyada URI, ad, bilinen size ve pozitif modified eşleşiyorsa sonuç tekrar kullanılır; görsel açılmaz. Metadata eksikse değişmezlik varsayılmaz. Bu durum bazı SAF sağlayıcılarında sonraki explicit Rescan'ın tekrar doğrulama yapmasına yol açabilir.

Aynı hesaba birden fazla aday varsa okunabilir aday, daha yüksek alan, format tercihi ve deterministik ad/URI sırası kullanılır. Tüm adaylar checkpoint tablosunda kalır; public JSON profillerinde seçilmiş bir aday bulunur. Bu nedenle çözünürlük denetimi yalnızca public profiles map'ini sayarsa duplicate dosyaları kaçırabilir. Yeni sayım bu ayrımı giderir.

## 8. Çözünürlük sayacının kesin anlamı

Target 256×256'dır. width>0 ve height>0 biliniyor, bunlardan biri 256 değilse dosya sayılır. 128×128, 64×64, 512×512 ve 256×128 örnekleri hedef dışıdır. Bilinmeyen width/height hedef dışı diye uydurulmaz; unknown sayılır. Boyutu bilinen fakat okunamayan adayın ölçüsü de bilgi amacıyla değerlendirilebilir; okunabilirlik sınıflandırması ayrıca korunur.

Sayım JSON'daki hesaplarla sınırlı değildir: taranan klasördeki tüm desteklenen adayları, duplicate'leri ve geçerli kullanıcı adıyla eşleşmeyen görselleri kapsar. Alt klasörler recursive gezilmez. Görsel olmayan dosyaya çözünürlük atfedilmez. Bu sayı son tamamlanmış indeksin bilgisidir; dışarıdan sonraki dosya değişiklikleri için explicit Rescan gerekir.

Yeni taramalar resolutionAudit alanını public JSON'a yazar. Eski kurulumda bu alan yoksa aynı scope'a ait tamamlanmış thumbnail-scan.db kaydı aggregate SQL ile kullanılır. completed bayrağı ve toplamlar aynı SQLite statement snapshot'ında okunur; yeni bir partial batch tamamlanmış sonuçla karışmaz. Sonuç cache'lenir. SAF dosyası açılmaz, yeni tam tarama başlatılmaz.

Tam checkpoint yoksa mevcut public profillerin bilinen ölçüleri gösterilebilir; bu sonuç complete değildir ve kart Rescan for full folder count der. Yeni sayı hiçbir dosyayı silmez, değiştirmez, yeniden kodlamaz, 128 görseli görüntülemeden çıkarmaz veya Fetcher'ı yeniden indirmeye zorlamaz. Fetcher'ın eski 128/256 uyumluluk kontrolü ayrı kalır.

## 9. Kesinti, checkpoint ve veri bütünlüğü

Batch tamamlandıkça sonuçlar özel SQLite'a kaydedilir. İşlem/uygulama kesilince en fazla commit edilmemiş mevcut batch'in işi tekrarlanabilir; önceki commit'ler korunur. Genel SAF API'si güvenilir bir değişim token'ı veya resume offset'i sağlamadığı için devamda metadata listesi tekrar alınabilir; tamamlanmış validation sonuçları kullanılabilir. Kaynak dosyalar topluca yeniden açılmaz.

Null, loading veya error işaretli cursor, başarısız hareket, izin kaybı ve bellek hatası tamamlanmış public indeksi kısmi listeyle değiştirmez. Aynı metadata ile gelen geçici FileNotFound tekrar kullanılabilir negatif sonuç sayılmaz. SQLite transaction hatasında batch rollback olur. OOM'da yeni işler durdurulur ve mümkün olan kaynaklar serbest bırakılır; kayıtlar körlemesine sıfırlanmaz.

Bu sürümün Rescan yolu salt okunurdur. Kaynak görüntülerin bytes, lastModified ve kendileri değişmez. Özel checkpoint'teki artık metadata satırlarının temizlenmesi gerçek SAF dosyasını silmez. Clear Thumbnail Index public/özel indeksleri, Clear Image Cache eski uygulama image kopyalarını temizler; seçilen thumbnail kaynaklarını ve koleksiyon JSON'unu silmez.

## 10. Rescan performansı: ölçüm ve alınmayan deneme

Bu görevde mevcut build 10 kodu 15.000 gerçek sentetik 128/256 WebP ile ölçüldü. Robolectric/API34 provider her kaynak açışında 1 ms yapay gecikme uygular. Başlangıç gözlemi cold 13.074 ms, warm 535 ms, cold source opens 15.000, warm opens 0 ve toplam query 2/2'dir.

Dosya başına Future yerine batch başına iki tüketici task denemesi 13.508 / 549 ms verdi. Ölçülebilir ek hız kazancı ortaya çıkmadığından bu ThumbnailScanner değişikliği sürüme alınmadı. Orijinal iki-worker akışı, doğrulama gücü ve checkpoint sınırı korundu. Son doğrulama ve CI ölçümleri rapor başındaki tabloda ayrı verilir. Küçük koşudan koşuya farklar kalıcı hız kazanımı diye sunulmaz.

Beklenen düşük maliyetli yol tamamlanmış metadata'yı kullanmaktır. SAF provider gecikmesi, bitmap decode, SQLite/public JSON işlemleri ve cihaz yükü gerçek süreyi etkiler. Worker sayısını kanıt olmadan artırmak provider'ı aşırı yükleyebilir; bu güncellemede artırılmadı. Fiziksel telefonun 14.572 gerçek dosyası bu çalışmada ölçülmedi.

## 11. Yerel thumbnail görüntüleme ve RAM

Virtual liste yalnızca görünür aralık ve sınırlı overscan için DOM üretir. Satır havuzu 32, browser Image elemanı havuzu 64, aynı anda image yükleme işi 6 ile sınırlıdır. Search normalize edilmiş yerel veriyi filtreler; görünmeyen hesabı da bulabilir. 10.000 hesap testinde DOM sınırlı kalır. Yeni modal stilleri bu algoritmayı değiştirmez.

LocalImages, normal /thumb isteğinde orijinal kaynak byte'larını verir; bitmap, yeniden örnekleme, encode veya yeni disk kopyası yapmaz. Aynı kullanıcı/fingerprint istekleri CompletableFuture ile birleştirilir. Native okuma kapasitesi 4'tür. /full yerel önizleme yolu eski bounded decode'u korur; en fazla 2048 px önizleme kullanır. Tam ekran pinch, pan ve double-tap davranışı korunur.

HD/remote tam görüntü RAM'de tutulur, viewer kapanınca byte dizisi sıfırlanıp bırakılır. Eski callback yeni viewer'ı açamaz. JavaScript Image ve token referansları da kapanınca temizlenir. Mevcut kaynak thumbnail, online görüntü bulunamadı diye silinmez. Kaynak üst sınırları mutlak bir toplam uygulama RAM kotası değildir; çok büyük kaynaklar ve Android renderer süreçleri ayrıca bellek kullanır.

## 12. Missing Images kopyalama zinciri

Düğme → copy-missing-users → MainActivity I/O executor → LocalImages.missingUsernames → MissingProfiles.text → UI thread ClipboardManager şeklinde ilerler. Seçili kataloğun tree'si ve erişilebilirliği, Missing Images sayacında kullanılan ölçütle aynıdır. Katalogdaki readable olmayan veya bulunmayan hesap eksik kabul edilir. Eski dış dosya değişiklikleri ancak Rescan veya okuma hatasıyla indekse yansır; kopyalama ekstra toplu disk kontrolü yapmaz.

LinkedHashSet normalleştirilmiş kullanıcı adını ilk liste sırasıyla bir kez tutar. Geçerli username karakter/uzunluk deseni dışındaki URL/klasör metni panoya yazılmaz. Çıktı yalnızca satır sonuyla ayrılmış kullanıcı adlarıdır; başlık, JSON, klasör yolu, @ veya madde işareti eklenmez. Native komut, front-end'in gönderdiği bir kullanıcı listesine güvenmez. İçerik diagnostics'e kaydedilmez.

Robolectric testinde Android ClipboardManager içeriği okunur. Browser preview testinde gerçek Chromium clipboard'u okunur; izin reddi ve boş liste de sınanır. Ek Android emülatör testi gerçek uygulama WebView düğme komutunu native köprüye yollar ve panodaki üç benzersiz sentetik adı kontrol eder. Bu, canlı hesap erişim testi değildir.

## 13. Fetcher katmanları ve kalıcı kuyruk

Fetcher public adapter, QueueEngine coordinator, FetchBroker dispatcher, ProfileProvider edinme katmanı ve OutputFolder güvenli yazıcıdır. Coordinator SQLite kuyruk ve SAF commit sahibidir; bağımsız profil operasyonları broker üzerinden çakışabilir. Kullanıcı adına göre bir hesap satırı, position ve status/phase bulunur. counts toplamı aktif JSON listesinin benzersiz hesaplarını verir.

SUCCESS yeni yazılmış başarılı indirme; EXISTING okunabilir mevcut thumbnail; SKIPPED_UNAVAILABLE seçilmiş hızlı metin politikasının erişilemez sonucu; SKIPPED_NO_IMAGE açık default avatar; FAILED profil işleme hatası; RETRY_LATER tamamlanmamış/geçici sonuçtur. Yeni başlatma, otomatik olarak geçmişi silmez.

Build 10'dan beri tamamlanmış başarısız profil isteği; FAILED, SKIPPED veya attempts>0 RETRY_LATER olarak özel fetch_outcomes kaydına alınır. Start Missing ve Resume bu hesapları yeniden açmaz. Retry Skipped / Failed Profiles açık kullanıcı eylemi bunları tekrar PENDING yapar. Başarılı dosyalar bundan etkilenmez. Interrupted claim, henüz başlamamış profil ve geçici oturum doğrulama beklemesi tamamlanmış hesap hatası sayılmaz.

JSON'dan çıkarılan hesap active accounts listesinden çıkabilir; özel outcome kaydı kalır. Aynı kullanıcı yeniden eklenirse geçmiş geri yüklenir. Gerçekten yeni kullanıcı adının eski kaydı olmadığından PENDING olur. Bu ayrım, tüm yeni JSON listesini yanlışlıkla eskiden başarısız sanmayı önler. Başarılı/atlanmış/geçici hata kayıtlarını toplu sıfırlama veya otomatik Retry eklenmedi.

## 14. Kapalı sayfa ve belirsiz profil ayrımı

Önceden kaydedilmiş Skip/Fail sonuçları hiçbir WebView açılmadan kuyruk uygunluğu filtresiyle geçilir. İlk kez gelen profil için login/challenge/rate-limit kontrolleri korunur. Kullanıcı tarafından seçilen hızlı politika, tanınan kullanılamayan sayfa metnini ve mevcut görsel adayı olmadığını gördüğünde PROFILE_PAGE_UNAVAILABLE üretip SKIPPED_UNAVAILABLE kaydeder. İngilizce/Türkçe kapalı sayfa ifadeleri fixture'larla testlidir.

HTTP 200 hesabın açık veya kapalı olduğunu tek başına kanıtlamaz. PolarisErrorRoot bileşeni de tek başına kalıcı atlama kanıtı değildir. Geçerli görsel adayı varsa sırf kapalı sayfa bileşeni/metin bayrağı nedeniyle erişilemez karar verilmez. Canonical/identity belirsizliği ve çözülemeyen birden fazla aday profil yokluğu sayılmaz.

WEBVIEW_TIMEOUT, ağ sorunu veya eksik görüntü URL'si hesabın silindiği kanıtı değildir. RETRY_LATER olarak kalabilir ve manuel Retry ile yeniden denenir. Hızlı metin politikasının sınırı vardır: Instagram açık bir hesaba geçici hata metni gösterirse ilk sınıflandırma yanlış olabilir. Uygulama, silinme/username değişikliği/private durumlarının her birini uzaktan kesin kanıtladığını iddia etmez. Bu yüzden Retry eylemi ve neden kodu korunur.

## 15. Oturum, cookie ve WebView yaşam döngüsü

CookieVault, AES-256-GCM ve Android Keystore anahtarıyla özel dosyada saklar. Cookie metni ve profil HTML'i diagnostics'e yazılmaz. Cookie'nin dosyada bulunması veya CookieManager'a başarılı uygulanması doğrulanmış Instagram oturumu anlamına gelmez. SHARED_COOKIES_APPLIED yalnızca aktarımı bildirir; authentication ayrıca çalışır. Evidence VERIFIED, UNVERIFIED ve UNKNOWN'dur; UNKNOWN kanıt eksikliğini ifade eder.

Her batch mevcut ProfileProvider.authenticate üzerinden profil isteklerinden önce oturumu denetler. Doğrulanmış geçersiz oturum yeni işleri durdurur. Geçici ağ/oturum doğrulama belirsizliği güvenli cooldown ile bekler; kalıcı cookie bozukluğu diye yeniden etiketlenmez. Gerçek cookie reject yalnızca platform cookie kabulünü reddettiğinde kullanılır.

Hidden WebView'ler main looper'da oluşturulur, profile özel operasyon tarafından kullanılır ve about:blank temizliğinden sonra havuza döner. Cookie/session alanı semaphore lease ile ortak yönetilir. Ana profil deadline'ı 45 saniye, startup watchdog 55 saniye, worker await 65 saniye; auth için daha kısa süreler vardır. Poll 500 ms'dir. Bu güncellemede bu zamanlayıcılar değiştirilmedi.

Bir işlem bitince callback/watchdog'lar temizlenir. Renderer, memory ve cleanup hataları ayrı kodlarla ele alınır. Kullanıcı Pause/Stop veya viewer kapatma iptalleri hesapların tamamını yanlış kapalı sınıfına çevirmemelidir. Gerçek WebView zamanlaması için emülatör offline DOM/CDN yanıtları kullanılır; canlı Instagram latency'si bu testten çıkarılamaz.

## 16. Worker ve schedule

Varsayılan aralık worker başına 15–30 saniye, varsayılan yerel çalışma penceresi 02:00–07:00'dır. Kullanıcının kaydettiği ayar geçerlidir; bu güncelleme varsayılanı veya mevcut ayarı değiştirmez. Gece yarısını aşan pencereler desteklenir. Pacing başlangıçtan başlangıca, her worker için ayrı workerNextAt kaydında kalır. Server cooldown bütün havuza uygulanır.

Requested WebViews 1–30 aralığındadır. ConcurrencyPolicy; sistem available memory, threshold, low-memory durumu ve Java heap headroom ile effective admission hesaplar. Bunlar ölçülmüş kesin sayfa boyutları değil, koruyucu tahminlerdir. Memory pressure veya renderer sorununda limit düşebilir. Requested ayar silinmez. Bu görevde Rescan veya Fetcher worker sayısı artırılmadı/azaltılmadı.

Viewer işi batch'e göre önceliklidir, aktif batch işleriyle güvenli sınırda aynı oturumu kullanır; rate-limit/cookie korumalarını aşmaz. Worker delay ekranda geri sayım olarak ayrılır. Beş başarısız hesap sonrası diğer hesapların devamı ve beş hazır worker'ın birlikte ilerlemesi mevcut regresyon testleriyle korunur. Cihazdaki her bekleme yeni bir hata olarak varsayılmaz.

## 17. Görsel indirme ve güvenli çıktı

PublicProfileProvider izin verilen HTTPS kaynakları ve redirect politikasını denetler. Bağlantı/read timeout 15 saniyedir. Kaynak byte sınırı 8 MiB'dir. ThumbnailCodec tam konteyner kontrolü, decode ve merkezden kare crop ile 256×256 WebP üretir. Çıktı en fazla 256 KiB'dir. Eski 128×128 ve yeni 256×256 WebP mevcut dosya doğrulamasında kabul edilir. Normal Rescan'daki bilgi hedefi bu Fetcher uyumluluk kuralını değiştirmez.

OutputFolder seçilmiş SAF konumuna yazar. Yeni veri geçici dosyaya yazılır, geri okunup doğrulanır, uygun provider destekliyorsa atomic rename/commit yapılır. Journal, backup ve kesilmiş yazmayı toparlama adımları vardır. Permission kaybı, rename desteği veya readback hatası başarılı indirme sayılmaz. Thumbnail yazma yetkisi açıkça alınmadan dosya commit edilmez. Yeni kullanıcı adı dosya adı user.webp'dir.

Mevcut dosya okunabilir olduğunda ağ indirmesi tekrarlanmaz. İlk Fetcher kataloğu normal indeksten metadata seed edebilir; sıcak başlatma tam klasör taramasını ve bitmap açmayı tekrar etmez. Explicit Verify / Repair Fetcher Index ayrı, kullanıcı başlatmalı işlemdir. Bu güncelleme hiçbir toplu yeniden doğrulama çağrısı eklemez.

## 18. Arka plan, pause ve bildirimler

FetchService kullanıcı başlatmalı foreground çalışmayı sahiplenir. WorkManager, background açık ve kuyruk armed/userPaused uygunken kurtarma ve sonraki iş planını saklar. Recovery periyodu 15 dakikadır; DownloadWorker bounded yaklaşık iki dakikalık pooled tur kullanır. Android Doze, quota, ağ kısıtı ve OEM davranışı işi erteleyebilir. Force Stop sonrasında Android'in yeniden çalıştırma kuralları geçerlidir.

Pause ilerlemeyi korur; Stop otomatik devam arm'ını da kapatır. Home veya görevi kaldırmak tek başına Pause değildir; background ayarı ve foreground service sahipliği uygulanır. Gerçek storage/SQLite/OOM sorunları kuyruk seviyesinde güvenli duraklatma yapar. Profil RuntimeException PROFILE_PROCESSING_ERROR olarak yalnızca ilgili profili etkiler. OOM sıradan profil hatası gibi yutulmaz.

Bildirim progress'i SQLite counts'tan türetilir; WebView başarılı göründü diye Downloaded artmaz. EXISTING başarı indirmesi sayılmaz. Yeni sade görünümde durum ve Downloaded tek satırdır; genişletmede Remaining/Existing/Skipped/Failed ve varsa manual Retry bilgisi vardır. Aktif bildirim Pause/Stop kontrolünü korur. Gerçek RATE_LIMITED için HTTP/cooldown ve otomatik/manual devam bilgisi kalır. Tamamlanma yaklaşık altı saniyede kaybolur; fatal ve session/rate-limit uyarısı kalıcı olabilir. Bildirim güncellemesi yaklaşık saniyede birle sınırlıdır.

## 19. Ayar yedekleme, mahremiyet ve ağ

Export All Settings; schedule/worker/background ayarları, seçili folder referansları ve cookie'leri parola ile taşınabilir şekilde şifreler. PBKDF2-HMAC-SHA256 210.000 iterasyon, 16 byte salt, AES-256-GCM ve 12 byte IV kullanılır. Parola en az 8 karakterdir. GCM AAD format, sürüm ve paket bağlamını içerir. Yanlış parola veya bozuk içerik aynı güvenli hata akışına girer.

Settings yedeği Favorites koleksiyonlarını, hesap kuyruğunu, outcome geçmişini, thumbnail indeksini veya kaynak görüntüleri taşımaz. Bunlar için ilgili ayrı JSON/veri akışları geçerlidir. SAF yetkisi JSON'dan geri getirilemez; Android grant'i ayrıca gerekir. Ayar import'undan sonra retrieval otomatik başlatılmaz.

App manifest INTERNET, foreground service/dataSync, notifications ve boot/time sinyalleri için gerekli izinleri kullanır. Broad storage izni yoktur. OS otomatik uygulama yedeği kapalıdır. Yeni clipboard özelliği ek izin istemez; yalnızca açık kullanıcı eylemiyle panoya yazar. Clipboard artık işletim sistemi ve kullanıcının panoya eriştirdiği uygulamalarca yönetilir; diagnostics'e aktarılmaz.

Fetcher diagnostics sabit kod ve alt nedenler, HTTP status biliniyorsa, page-finished/candidate evidence, session state, elapsed/timeout, worker ve queue sayıları içerir. Kullanıcı adı, tam URL, cookie, token veya HTML içermez. Session aktarım ve doğrulama olayları ayrı tutulur. PRIVATE outcome ledger içindeki adlar dışa aktarılmaz; yalnızca kayıt sayısı raporlanır.

## 20. Test stratejisi ve sınırlar

Zorunlu yerel komutlar ./gradlew :app:testDebugUnitTest ve ./gradlew :app:lintDebug çalıştırıldı. Release/debug/androidTest derlemeleri de yapılır. Native testler SQLite reopen/migration, atomic rollback, SAF cursor/permission/interruption, thumbnail konteyneri, session/cookie, timeout, queue continue, worker pacing, notification ve yeni clipboard/audit kodunu kapsar. JS core testleri JSON/arama/virtualization algoritmasını, Chromium testleri arayüz/focus/transition/clipboard akışını sınar.

CI aynı kaynak commit'i için build, native test, lint, minify reproducibility, büyük liste UI ve offline profile-probe fixture'larını çalıştırır. Android 14 emülatörü gerçek Binder SAF üzerinde 15.000 sentetik dosya, gerçek Chromium pool ve Home/ekran kapalı/görev kaldırma fixture'ı kullanır. Yeni denetimde bu dosyaların 7.500 adet 128×128'i hedef dışı sayılmalıdır. Native clipboard kontrolü uygulama köprüsünden geçer ve yalnızca üç sentetik username'i doğrular.

Gerçek Instagram doğrulanmadı. Kullanıcının gerçek SAF sağlayıcısı, fiziksel reboot/Force Stop/OEM kısıtları ve fiziksel GPU performansı bu çalışmada ölçülmedi. SQLite bozulması otomatik silinerek gizlenmez. Önceki benchmark'larla yeni ölçüm farklı cihaz/koşulda mutlak hız garantisi vermez. Bilinmeyen dosya ölçüsü kapalı hesap veya geçersiz boyut diye varsayılmaz.

## 21. Kaynakla dokümantasyon arasındaki bilinen eski ayrıntılar

Settings altındaki eski açıklamanın bir kısmı Rescan'ın orphan dosya silebileceğini söyler; kullanıcı bu görevde diğer içeriğe dokunulmamasını istediği için o metin değiştirilmedi. Mevcut kaynakta Rescan salt okunurdur ve bu rapor gerçek işleyişi esas alır. Kaynak yazma/silme sayısı testte sıfırdır.

PublicProfileProvider User-Agent son eki eski INSFAVThumbFetcher/0.3.0 değerini taşır. Bu, Android uygulamasının versionName veya versionCode kaynağı değildir. Bu görev arayüz/denetim/bildirimle sınırlandığından ağ kimliği değiştirilmedi. Güncel Android sürümü version.properties ve APK manifestinden doğrulanır.

## 22. Derleme ve yeniden üretim

Proje Java 17, Gradle wrapper 8.13 ve Android compileSdk/targetSdk 35, minSdk 26 kullanır. Ana runtime bağımlılığı WorkManager 2.10.1'dir; native testte JUnit/Robolectric kullanılır. npm ci ve npm run web paketli web kaynaklarını üretir. Ardından testDebugUnitTest, lintDebug ve assembleRelease çalışır. Release signing environment konfigürasyonu mevcut Gradle signingConfig üzerinden alınabilir; özel anahtar kaynak ZIP'ine veya rapora eklenmez. Debug anahtarı release anahtarının yerine sunulmaz.

Bu APK, önceki build 10 ile aynı mevcut sertifikayla imzalanır. Manifest package/version/build, non-debuggable durumu, ZIP alignment ve APK imzası kontrol edilir. Yayın öncesi candidate hash'leri doğrulanır, taslak dosyalar tekrar indirilir, SHA256SUMS denetlenir. Önceki APK ayrı sürümde korunur. Aynı sürüm/build nedeniyle doğru dosyayı belirleyen işaret yeni commit ve APK SHA-256'dır.

## 23. Modül haritası

| Modül | Sorumluluk |
|---|---|
| MainActivity / InsfavApplication | Activity, yerel WebView, bridge, OS lifecycle ve Store↔Fetcher host bağlantısı. |
| Store / DoubleBackExit | Atomic JSON/ayar/indeks, normalize kullanıcı kümesi, sayaçlarla uyumlu orijinal veri; iki saniyelik kök ekrandan çıkış. |
| LocalImages / ImageCodec | SAF okuma, katalog snapshot, orijinal thumbnail byte servisi, bounded decode/preview. |
| ThumbnailPolicy / ThumbnailScanner / ThumbnailScanIndex | Aday seçimi, bulk tarama, checkpoint, transaction, reuse ve tüm-dosya metadata'sı. |
| MissingProfiles / ResolutionAudit | Yetkili eksik kullanıcı adı panosu ve yalnızca bilgi amaçlı strict 256 ölçümü. |
| web/core.js / web/app.js / web/style.css | JSON view modeli, search/virtual rows, navigation/modals/viewer, Settings/Fetcher görünümü. |
| Fetcher / FetcherCatalog | Public adapter, listener/snapshot, normal katalogdan Fetcher metadata seed ve resolution/observer desteği. |
| QueueEngine / QueueStore / QueueControl | Tek coordinator, SQLite durum/ledger, claim ve pause/cancel sahipliği. |
| FetchBroker / WorkerPacing / ConcurrencyPolicy | Öncelikli dispatch, worker bağımsız pacing, admission ve gerçek provider operasyonlarının çakışması. |
| ProfileProvider / WebViewProfileProvider | Oturum doğrulama, hidden WebView havuzu ve deadline/callback yaşam döngüsü. |
| profile-probe.js / profile-probe-hd.js / ProbeDecision | DOM/identity/görsel adayı ve hata kararları; HD edinme için ayrı probe. |
| PublicProfileProvider / WebProfilePolicy / AccessWall | HTTPS image transfer, güvenilen host/navigation, erişim/login/challenge/rate-limit sınıflandırması. |
| ThumbnailCodec / OutputFolder / Identity | 256 WebP üretimi, 128/256 uyumlu doğrulama, güvenli SAF commit ve username dosya adı. |
| CookieJar / CookieVault / SessionState | Cookie parsing/expiry, Keystore şifreleme ve oturum kanıtı/blok durumları. |
| RunPolicy / ScheduleStatus / WorkScheduler | Yerel saat pencereleri, schedule durumu ve kalıcı WorkManager planları. |
| FetchService / DownloadWorker / RecoveryWorker | Foreground çalışma, bounded recovery ve OS lifecycle sahipliği. |
| ScheduleReceiver / QueueControlReceiver | Boot/time değişimi ve notification Pause/Stop eylemleri. |
| DownloadNotification / Diagnostics / FailurePolicy | Sade queue-backed bildirim, redacted kanıt ve hesap/global hata politikası. |
| SettingsArchive / SettingsBackupUi | Parolalı settings/cookie yedeği ve native import/export akışı. |
| app/src/test / app/src/androidTest / tests | JVM/Robolectric, gerçek emülatör ve Chromium/JS testleri. |
| tools / .github/workflows | Web build, ölçüm/rapor paketleme, CI ve imza doğrulamalı yayın kapıları. |

## 24. Değişikliklerin dosya/metot ve test eşlemesi

Bu görevdeki satır satır önce/sonra değişiklik ve test eşlemesi docs/UI-AND-AUDIT-BUILD10.md dosyasındadır; kaynak ekinde de bulunur. Verilen davranışlar yalnızca otomatik testin kapsadığı sınırlar için doğrulanmıştır. Başarısız geliştirme denemeleri, performans denemesinin geri alınması ve son test raporları kanıt paketinde saklanır. Raporun altındaki kodlar kırpılmaz; tüm metin kaynakları ve birebir kaynak ZIP'i birlikte sunulur.
