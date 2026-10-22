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
