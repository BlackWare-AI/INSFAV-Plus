# HD, uygulama içi güncelleme ve yedekleme

- Aynı profil fotoğrafının doğrulanmış HD önbelleği yeniden kullanılır. Thumbnail dosyasının gerçek SHA-256 değeri de eşleşiyorsa tekrar 256×256 dönüşüm ve yazma yapılmaz. Eksik/değişmiş thumb onarılır; yeni profil fotoğrafı eskisinin yerini alır. Önbellek silinirse veya kaynak doğrulanamazsa yeniden indirme gerekebilir.
- Ayarlar'ın en altında uygulamanın kendi GitHub Release sayfasından güncelleme kontrolü eklendi. Otomatik kontrol, APK indirme ve incelenmiş güncellemenin kurulum ekranını açma tercihleri kalıcı olarak kaydedilir; varsayılan kapalıdır.
- Güncelleme bildirimi, yüklemeden önce yenilik/düzeltme önizlemesi ve sonradan yeniden açılabilen yayın notu geçmişi eklendi.
- APK'nın boyutu, sağlanmışsa SHA-256 özeti, paket kimliği, artan build numarası ve mevcut kurulumla aynı imzası doğrulanır. Android'in kaynak izni ve son kurulum onayı korunur; sessiz arka plan kurulumu yapılmaz.
- İsteğe bağlı kurulum öncesi şifreli yedek; seçilen klasöre favorileri, ayarları, çerezleri, indeksleri, kuyruk/SQLite verilerini, geçmişi ve logları kaydeder. Yedek geri okunup doğrulanmadan kurulum başlamaz. Harici thumbnail dosyaları, HD önbelleği ve WebView site depolaması kopyalanmaz; mevcut dosyalar yerinde kalır.
- Yedek PIN'i yan yana dört görünür rakam kutusuyla girilir. Eski uzun parolalı ayar yedekleri açılmaya devam eder; AES-GCM koruması korunur.
- Legacy/YTDLnis seçim penceresi mevcut kontrol konumunda AMOLED tasarıma uyarlandı. Diğer INSFAV uygulamasının GitHub bağlantısı ve güncel kullanım/yedekleme belgeleri eklendi.
- Mevcut Legacy, YTDLnis tarzı ve manuel çerez yöntemleri, kuyruk, görsel biçimleri, paket kimlikleri ve imzalama kimlikleri korunur. Zorunlu çerez testi eklenmedi.

Otomatik birim, tarayıcı ve Android emülatör testleri kontrollü yanıtlar/hesaplar kullanır. Canlı Instagram hesabıyla başarı veya fiziksel cihazda güncelleme kurulumu test edilmiş olarak raporlanmaz. Instagram'ın iptal ettiği oturumu yedek geçerli hale getiremez.
