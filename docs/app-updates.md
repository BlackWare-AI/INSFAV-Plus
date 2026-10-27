# Uygulama içi güncelleme ve veri yedeği

Henüz APK yayımlanmadan hazırlanmış kaynak değişiklikleridir. INSFAV ve Plus kendi paketlerini, Release kaynaklarını, tercihlerini ve oturumlarını kullanır. Otomatik kontrol/indirme/kurulum ekranı tercihleri varsayılan kapalıdır. Kontrol ve indirme WorkManager ile yapılır; sürekli çalışan servis eklenmez. Android zorla durdurulmuş uygulamada arka plan işleri için yeniden açılış bekleyebilir.

## Güncelleme sırası

1. Yalnızca ilgili deponun GitHub API `/releases/latest` kararlı yayını okunur. Taslak/ön sürümler ve birden çok belirsiz APK kabul edilmez.
2. Mevcut sürüm kurallarına göre daha yeni sürüm belirlenir; APK içindeki versionCode kurulum öncesinde ayrıca doğrulanır.
3. Bildirim ve Ayarlar kartı güncellemeyi haber verir. İnceleme ekranı yayın notlarını düz metin olarak gösterir. Son 50 görülen sürümün notları özel depolamada saklanır ve sonradan açılabilir.
4. APK yalnızca bu deponun GitHub Release bağlantısından, HTTPS ve sınırlı güvenilir yönlendirmelerle indirilir. Başarısız/yarım dosya temizlenir. Boyut, sağlanmışsa GitHub digest’i, uygulama paket kimliği, imza ve artan versionCode doğrulanır. Kullanıcının önceden onayladığı farklı keystore’larla kurulmuş mevcut uygulamalar, aynı güncel imza ile güncellenir; başka imzayla güncelleme kabul edilmez.
5. Her yeni yayın için inceleme sonrası devam edilir. Kalıcı otomatik kurulum seçimi, incelenmiş ve indirilmiş sürümün kurulum ekranını uygulama ön plandayken açar. Android kaynak izni ve son kurulum onayı zorunludur; sessiz/aralıksız arka plan kurulumu vaat edilmez.
6. Yedek seçilmişse klasör erişimi ve görünür dört rakamlı PIN istenir. Aktif tarama, toplu indirme, HD işi veya dönüşüm varsa kurulum durur; kullanıcı işlemleri bitirip tekrar deneyebilir. Başarılı yedek olmadan yükleyici açılmaz. Yedekleme sırasında veri değiştiren arayüz komutları geçici olarak engellenir; bitince tekrar kullanılabilir.
7. İncelenen APK özel depolamada sabit kopyaya alınır ve yeniden özetlenir. Bu sırada yeni bir yayın bulunması, yeni APK’nın incelemesiz kurulmasına yol açmaz. Android yükleyicisine yalnızca bu kopyanın dar kapsamlı FileProvider URI’si verilir.

## Yedek kapsamı ve biçimi

`.ifbackup` bir AES-256-GCM şifreli ZIP arşividir. Düz metin geçici dosya oluşturulmaz. Başlık: ASCII `INSFAV-BACKUP-2\n`, 16 bayt salt, 8 bayt nonce önekidir. ZIP akışı en fazla 256 KiB parçalarla şifrelenir: 4 bayt big-endian düz parça uzunluğu, şifreli parça ve 16 bayt GCM tag’i. Son boş parça ayrıca doğrulanır; eksik son parça, sıra değişimi ve sonda fazladan veri reddedilir. Nonce, 8 bayt önek + 4 bayt sıra numarasıdır. AAD, paket kimliği + 4 bayt sıra + son-parça bayrağıdır. Böylece büyük SQLite yedekleri bütünüyle belleğe alınmaz. Anahtar PBKDF2-HMAC-SHA256, 210000 tur ile PIN’den türetilir. Paket kimliği AAD içinde UTF-8 baytlarıyla yer alır. Her yedekte yeni rastgele salt/nonce öneki kullanılır. Dört rakamlı PIN düşük tahmin alanına sahip olduğundan arşivi güvenli bir klasörde tutun.

Arşiv şunları içerir:

- `manifest.json`: uygulama, zaman ve kapsam.
- `portable/settings.json`: mevcut JSON ayar arşivi (aynı PIN ile şifreli) ve uygulamanın kayıtlı manuel/Legacy/Netscape çerez kayıtları. Bu dosya da dış arşivin şifrelemesi içindedir.
- `portable/instagram-webview-cookie-header.txt`: görünür WebView oturumundan CookieManager API’siyle alınmış Instagram Cookie Header.
- `files/`: özgün Favoriler JSON’u, ayarlar, indeksler, doğrulama kayıtları, geçmiş ve loglar dahil uygulamanın özel dosyaları.
- `shared_prefs/`: görünüm, dil, modül, oturum ve güncelleme tercihleri dahil özel tercihler.
- `databases/`: yazıcı kilidi altındaki SQLite veritabanı ve varsa WAL çiftleri; SHM yeniden oluşturulabilir.
- `pending-hd-jobs/`: varsa tamamlanmamış yerel HD dönüşüm kayıtları.

Android Keystore anahtarları dışa aktarılmaz. Ham `.enc` çerez dosyaları başka bir kurulumda tek başına çözülemez; taşınabilir çerez kopyaları bu nedenle ayrıca şifreli arşiv içine alınır. Harici `.thumbnails` klasörü, HD cache ve WebView site depolaması arşive eklenmez. Uygulama güncellemesi bu verileri silmez. Arşiv, Instagram’ın iptal ettiği oturumu geçerli hale getirmez.

Dosya önce `.partial` adıyla oluşturulur, tamamı geri okunup GCM doğrulaması yapılır, sonra son adına çevrilir. Oluşturma, okuma, doğrulama veya yeniden adlandırma başarısızsa kurulum başlatılmaz. Seçilen SAF sağlayıcısının okuma/yazma ve yeniden adlandırmayı desteklemesi gerekir.

## Kurtarma

Normal yerinde güncelleme veri silmez ve yedeği kendiliğinden geri yüklemez. Ayarların eski JSON yedeği içe aktarma yolu korunur; yeni PIN dört rakamdır, eski uzun parola seçeneği ayrıca bulunur.

Tam arşivdeki veriyi incelemek veya bağımsız kurtarmak için `tools/decrypt-update-backup.py` kullanılabilir (`cryptography` Python paketi gerekir). Araç tüm GCM doğrulaması geçmeden çıktı ZIP’ini sonlandırmaz. Çıktı ZIP’i açık çerez içerir; yalnızca özel bir dizinde açın. `files/document.json` özgün Favoriler dosyası olarak içe aktarılabilir. `portable/settings.json` içindeki `settingsArchive` nesnesi JSON olarak kaydedilip aynı PIN ile mevcut **Tüm Ayarları İçe Aktar** işlevinde açılabilir; SAF klasör izinlerinin yeniden verilmesi gerekebilir. WebView Cookie Header manuel çerez alanında kullanılabilir. Ham SQLite/özel dosyaları çalışan uygulamanın üzerine kopyalamayın; bunlar uygulamaya özgü kurtarma kayıtlarıdır. Tam ham veri için otomatik geri yükleme arayüzü bu değişikliğin kapsamına eklenmemiştir.

## Doğrulama sınırı

Birim testleri ve kontrollü tarayıcı testleri gerçek hesap/çerez kullanmadan çalışır. GitHub üzerinden gerçek yayın indirme, imzalı gerçek APK ile cihazda kurulum, WorkManager’ın gerçek cihaz zamanlaması ve farklı SAF sağlayıcıları bu testlerle otomatik olarak başarılı sayılmaz. APK yayımlanmadıkça bu özellikler yayımlanan eski uygulamada bulunmaz.

Yeni tarayıcı akışı testi: Playwright ve Chromium kurulu ortamda `CHROME_BIN=/path/to/chrome node tests/update-panel.cjs`. Test gerçek GitHub/Instagram erişimi veya kurulum yapmadan native köprüyü taklit eder. Plus’ın mevcut Chromium test çalıştırıcısına da eklenmiştir.
