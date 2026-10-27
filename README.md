# INSFAV Plus

<img src="web/static/logo.jpg" alt="INSFAV Plus" width="128">

[Son yayımlanan imzalı APK](https://github.com/BlackWare-AI/INSFAV-Plus/releases/latest)

INSFAV Plus, InstaPro’dan dışa aktarılmış Instagram Favoriler koleksiyonlarını Android’de görüntülemek ve yönetmek için bağımsız bir uygulamadır. Instagram ile favori eşitlemesi yapmaz. [INSFAV](https://github.com/BlackWare-AI/INSFAV) ayrı bir uygulamadır; paketleri, verileri ve Instagram oturumları birbirinden bağımsızdır.

## Başlarken

1. SamMods InstaPro 15.30 veya daha eski, uyumlu bir sürümden alınmış JSON yedeğini içe aktarın. Uygulama `favorite_page_pref` alanındaki koleksiyonları okur ve özgün JSON’u korur.
2. Yerel profil görselleri için `.thumbnails` klasörünüzü Android’in klasör seçicisinden seçin. Paylaşımlı depolamanın tamamına erişim gerekmez.
3. Çevrimiçi HD görüntüleme için Ayarlar → Thumbnail Fetcher → Instagram bölümünden giriş yapın. İki aşamalı doğrulama ve güvenlik kontrollerini görünür giriş sayfasında kendiniz tamamlayın.
4. Toplu thumbnail indirmek isterseniz klasöre yazma izni verin ve **Eksikleri Başlat** veya **Devam Et** seçin. Uygulamayı açmak kendi başına toplu indirmeyi başlatmaz.

## Neler yapabilir?

- Koleksiyonları, hesapları ve koleksiyon üyeliklerini gösterir; arama ve liste / 2–5 sütunlu matris görünümü sunar.
- AMOLED siyah tasarım, Türkçe / İngilizce arayüz ve kalıcı görünüm ayarları kullanır.
- Yerel WebP thumbnail’ları ve çevrimiçi HD profil görsellerini görüntüler. HD için önceden thumbnail dosyası bulunması gerekmez.
- HD görselden 256×256 WebP thumbnail’ı bağımsız arka plan işiyle kaydeder; eksik thumbnail için dönüşüm göstergesi sunar. Görüntüleyicide başka profile geçmek yerel dönüşümü iptal etmez.
- İsteğe bağlı Thumbnail Fetcher’da kalıcı kuyruk, zamanlama, eşzamanlılık, duraklatma/devam etme, başarısızları yeniden deneme ve tanılama dışa aktarma bulunur.
- Fetcher tamamen kapatılabilir; toplu bağlantılar, zamanlama ve indirme bildirimleri durur. HD görüntüleme ve onun thumbnail dönüşümü kullanılabilir.
- JSON dışa aktarma ve uygulamaya özgü AES-GCM şifreli ayar/çerez yedeği bulunur. Normal ayar yedeği, Favoriler JSON yedeğinden ayrıdır.

## Instagram oturumu

- **Legacy · Kalıcı WebView:** Uygulamanın kendi kalıcı WebView oturumunu kullanır; varsayılan yöntemdir.
- **YTDLnis tarzı · Netscape / HTTP:** Aynı uygulamanın WebView çerezlerini Netscape biçimine dönüştürerek HTTP sağlayıcısına aktarır. YTDLnis’ten bağımsız uygulanmıştır; Instagram’ın tüm hesaplar için bu yolu kabul edeceği garanti edilmez.
- **Manuel çerezler:** `cookies.txt` veya HTTP Cookie Header yapıştırma, kaydetme ve kaldırma korunur. Alternatif yöntemler birbirlerinin kayıtlarını silmez.

Giriş ekranında yenileme, çerezi görüntüleme ve çıkış seçenekleri bulunur. Zorunlu **Test Session** adımı yoktur. Çerezler tanılama raporlarına eklenmez; başka uygulamalarla paylaşılmaz. Uygulama açılışında WebView verileri temizlenmez. Instagram sunucusunda iptal edilmiş veya süresi dolmuş bir oturum uygulama tarafından korunamaz. Güvenlik doğrulaması gerektiğinde yeni istekler durur; kullanıcı tamamlamadan otomatik devam edilmez.

## Hazırlanan, henüz APK olarak yayımlanmamış değişiklikler

Bu bölüm kaynak daldaki hazırlıkları anlatır. Yukarıdaki son yayımlanan APK bu değişiklikleri henüz içermeyebilir; bu çalışma sürüm/build numarasını yükseltmez.

- **Tekrarlanan HD işlemlerini doğrulama:** Her açılışta güncel profil kimliği kontrol edilir. Aynı fotoğrafın kimliği ve doğrulanmış HD önbelleği eşleşirse görsel gövdesi yeniden indirilmez. Thumbnail’ın gerçek SHA-256 değeri de eşleşiyorsa dönüşüm/yazma atlanır. Eksik/değiştirilmiş dosya onarılır; yeni fotoğraf eskisini günceller. Önbellek kaybında veya doğrulanamayan değişken URL’lerde yeni indirme gerekebilir.
- **Uygulama içi güncelleme:** Ayarlar’ın en altında yalnızca bu uygulamanın [kendi GitHub Releases sayfası](https://github.com/BlackWare-AI/INSFAV-Plus/releases) kontrol edilir. Otomatik kontrol, indirme ve inceleme sonrası kurulum ekranını açma izinleri ayrı ve kalıcıdır; başlangıçta kapalıdır. Kontrol yaklaşık 12 saatte birdir; Android zamanlamayı geciktirebilir.
- **İncelenebilir yayın notları:** Güncelleme butonuna ilk dokunuşta yenilikler/düzeltmeler gösterilir. Son 50 görülen sürümün notları aynı panelden yeniden açılabilir. Notlar çalıştırılabilir HTML olarak işlenmez.
- **Güvenli güncelleme yolu:** İndirilen APK’nın boyutu, varsa GitHub SHA-256 özeti, paket kimliği, artan versionCode’u ve mevcut kurulumla aynı imzası kontrol edilir. Hatalı/eksik paket kuruluma gönderilmez. Android’in kaynak izni ve kurulum onayı korunur; sıradan Android uygulaması arka planda sessiz kurulum yapamaz. Otomatik indirme arka planda yapılabilir; inceleme, yedek PIN’i ve kurulum ekranı için uygulamaya dönülür.
- **Kurulum öncesi tam veri yedeği:** Seçilen klasöre favoriler, tüm özel ayarlar, manuel/WebView çerezleri, indeksler, SQLite verileri, kuyruk, geçmiş ve loglardan şifreli `.ifbackup` arşivi yazılır ve geri okunarak doğrulanır. Başarısız yedek kurulumu engeller. Aktif tarama/indirme/dönüşüm bitmeden yedek alınmaz. Harici thumbnail dosyaları, HD önbelleği ve WebView site depolaması kopyalanmaz; mevcut dosyalar yerinde kalır. Oturumun sunucuda geçersizleşmesini bu yedek engellemez.
- **Dört rakamlı PIN:** Yeni ayar yedekleri ve güncelleme yedekleri, yan yana ve görünür dört rakam kutusu kullanır. Eski 8+ karakterli parolalı ayar yedekleri açılabilir. AES-GCM ve PBKDF2 yapısı korunur; kısa PIN nedeniyle yedeği güvenli klasörde saklayın.
- **AMOLED seçim penceresi:** Legacy/YTDLnis seçimi, aynı konumdaki düğmeden siyah seçim penceresiyle açılır.

Tam veri yedeği ve kurtarma ayrıntıları: [docs/app-updates.md](docs/app-updates.md).

## Derleme ve doğrulama

JDK 17, Android SDK 35 / build-tools 35.0.0 ve Node.js kullanılır.

```sh
npm ci
npm run web
npm test
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Arayüz kaynakları `web/` içindedir; `npm run web` paketlenmiş varlıkları yeniden üretir. Paket kimliği `com.instagram.insfav.plus` olarak korunur. Release APK’ları mevcut GitHub Actions imzalama akışıyla üretilir. Yeni sürüm yayımlanmadan sürüm numarası/build değiştirilmez ve imzasız paket dağıtılmaz.

Gerçek Instagram erişimi, hesap kısıtları, Android WebView sürümü ve ağ koşullarına bağlıdır. Kontrollü otomatik testlerin geçmesi her hesapta canlı erişimin doğrulandığı anlamına gelmez.
