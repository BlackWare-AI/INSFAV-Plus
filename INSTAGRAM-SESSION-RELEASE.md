# Kalıcı Instagram çerezleri ve HD bağlantısı

- YTDLnis seçeneğinde çerezler canlı WebView'dan okunup Netscape biçiminde şifreli olarak saklanır. Eksik veya uyumsuz Chromium veritabanı oturumu engellemez; eski veritabanı değeri canlı çerezin yerine geçmez.
- YTDLnis seçiliyken HD görüntüleyici mevcut kalıcı WebView oturumunu kullanır. Zorunlu HTTP API önkoşulu veya çerez testi yoktur. Manuel çerez alternatifi ve Legacy korunur.
- Instagram'ın HD gezinmesi sırasında değiştirdiği çerezler seçili yöntemin şifreli kaydına aktarılır. Aynı kayıt yeniden şifrelenip yazılmaz. Oturum açılışta silinmez veya yeniden kurulmaz.
- Her iki yöntemin Instagram ekranı AMOLED tasarımına ve Türkçe/İngilizce uygulama tercihine uyarlandı. Yenileme, canlı çerezler ve gerçekten kaydedilmiş Netscape kopyası ayrı kontrollerle görülebilir.
- Seçili yöntemin çıkarılmış çerez kaydını silme eklendi. Diğer yöntem, manuel çerezler ve canlı Instagram oturumu korunur; silinen kayıt kullanıcı yeniden Kaydet diyene kadar otomatik oluşturulmaz. Instagram'dan çıkış ayrı ve onaylı bir işlemdir.
- Gerçek güvenlik doğrulamasında indirmeler duraklar. Çerez kaydetmek toplu kuyruğu başlatmaz. HD önbelleği, arka planda 256×256 WebP dönüşümü ve yinelenen görsel doğrulaması korunur.
- Normal Android WebView tarayıcı kimliği ve mevcut imzalar korunur; yeni izin eklenmedi. Çerez değerleri tanılama kayıtlarına yazılmaz.

Otomatik testler kontrollü çevrimdışı oturumlar kullanır; gerçek Instagram hesabıyla veya fiziksel cihazda başarı iddiası değildir. Instagram'ın iptal ettiği veya süresi dolmuş bir oturum korunamaz.
