Instagram oturumu ve HD görüntüleyici:

- Legacy kalıcı WebView varsayılan yöntem oldu. Çalışmayan Current/HTTP seçeneği kaldırıldı; önceki bu seçim Legacy'ye taşınır. YTDLnis tarzı seçenek ve manuel çerez kayıtları korunur; YTDLnis yolu bu sürümde değiştirilmedi.
- Tamamlanan HD indirmesi özel yerel önbelleğe devredilir. 256×256 WebP oluşturma ve klasöre yazım, görüntüleyiciden bağımsız tek işçide sürer. Ekran/profil değişimi tamamlanmış işi iptal etmez.
- HD gösterilmeden önce yapılan gereksiz WebP dönüşümü kaldırıldı. Eksik küçük resim oluşturulurken HD ekranının sol altında dönen simge görünür.
- Uygulama süreci kapanırsa özel önbellekte kalan işler sonraki açılışta uygun klasör için yeniden alınır. Android önbelleği temizlerse kaynak yeniden HD görüntülemeyle alınmalıdır; sürekli servis veya yeni izin eklenmedi.
- Zorunlu çerez testi eklenmedi. Mevcut oturum, kuyruk ve veri biçimleri korunur.

Otomatik testler kontrollü ağ yanıtları kullanır. Canlı Instagram hesabıyla başarı bu sürüm için test edilmemiştir.
