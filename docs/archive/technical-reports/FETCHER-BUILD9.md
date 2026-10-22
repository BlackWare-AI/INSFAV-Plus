# INSFAV 5.0.0 — Build 9: Fetcher hızlı atlama

Uygulama sürüm adı **5.0.0** olarak kaldı; Android versionCode **8 → 9**.
Bu build, önceki tanılama/oturum/kuyruk düzeltmeleri üzerine kullanıcının
onayladığı metin temelli hızlı atlama politikasını uygular.

## Davranış

Profilde aşağıdaki metinlerden biri varsa ve **mevcut bir güvenilir, kullanıcıya
bağlı görsel adayı yoksa**, ilk probe sonucu `PROFILE_PAGE_UNAVAILABLE /
TEXT_MATCH` olur. Java, boş olmayan profil kullanıcısı ve `unavailableText=true`
ile `confirmedUnavailable=true` üretir. HTTP 404, doğrulanmış oturum,
pageReady/searchCompleted ve geçmişte aday görülmemiş olması bu kararın şartı
değildir. `FailurePolicy` hâlâ yalnızca confirmedUnavailable bayrağıyla
SKIPPED_UNAVAILABLE seçer.

- `sorry, this page isn't available` (tipografik apostrof da desteklenir)
- `bu sayfa kullanılamıyor`
- `bu sayfaya ulaşılamıyor`
- `sayfa kaldırılmış`
- `bağlantı bozuk olabilir`

Metin kontrolü canonical, ErrorRoot, default-avatar ve görselin yüklenmesini
bekleme kararlarından öncedir. İstekteki “önce hemen dön” ve “aday varsa atlama”
şartlarını birlikte karşılamak için, mevcut sınırlandırılmış JSON/header aday
araması tamamlanır; ağdan görsel yüklenmesi veya deadline beklenmez. Login ve
challenge işaretleri önceliklidir. Mevcut rate-limit engeli de korunur; metinle
birlikte rate-limit uyarısı görünürse RATE_LIMITED olarak kalır.

Probe, hızlı atlama yanıtında istenen `pageReady=true` ve `searchCompleted=true`
alanlarını yazar. Bunlar bu politika için değerlendirme tamamlandı işaretleridir;
Android'in gerçek `onPageFinished` kanıtı diagnostics'te ayrı `pageFinished`
alanında tutulur. Normal HTTP 200 başarı kodu WebView tarafından gözlenmediğinde
`http=0` (bilinmiyor) olarak kalır; 200 uydurulmaz.

Bu tercih, geçici hata metni gösteren açık bir profilin de SKIPPED_UNAVAILABLE
olmasına izin verir. Böyle kayıtlar kullanıcı tarafından mevcut Retry Skipped
ile yeniden denenebilir. Bu build geçmiş skipped kayıtlarını otomatik yeniden
kuyruğa almaz ve thumbnail dosyalarını silmez/taramaz.

## Dosya ve metot bazlı değişiklikler

| Dosya / metot | Önce | Sonra | Doğrulama |
|---|---|---|---|
| `app/src/main/assets/profile-probe.js` / ana fonksiyon | Unavailable metni, canonical ve görsel çözümlemesinden sonra değerlendirilirdi. | Beş metin, candidate istisnasıyla canonical'dan önce hızlı sonuç verir. Gate/rate-limit önceliği korunur. | `tests/probe-classification.cjs`: Türkçe/İngilizce, eksik yükleme, canonical mismatch, görselli açık profil, JSON adayı, login/challenge/rate-limit; offline fixtures. |
| `app/src/main/assets/profile-probe-hd.js` / ana fonksiyon | Metin temelli hızlı karar yoktu. | Aynı metinler ve aday koruması; görsel adayı varsa ErrorRoot tek başına atlatmaz. HD görsel seçimi korunur. | Aynı dosyada her iki asset için aynı fixtures; `tests/probe-browser.cjs` HD regresyonu. |
| `ProbeDecision.failure()` / PROFILE_PAGE_UNAVAILABLE | Doğrulanmış session + HTTP 404 + tamamlanmış arama ve metin gerekirdi. | Güncel candidateFound true ise normal akış; aksi halde profil kullanıcısı + explicit unavailableText yeterli. Root-only sonuç retry kalır. | `ProbeDecisionTest.explicitTextSkipsWithoutHttpSessionReadinessOrHistoricalCandidateRequirements`, `currentImageCandidateVetoesFastSkip`, `repeatedRoutedErrorsWithoutExplicitTextRemainRetryable`. |
| `WebViewProfileProvider.Operation.poll()` | Önceki sürümde zaten failure sonucunu hemen fail() ile bitiriyordu. | **Kod değişmedi**; yeni kararı ilk poll'de tamamlar. Deadline/ağ/cookie/challenge yolu korunur. | `ProviderOperationTest.explicitEnglishAndTurkishTextFinishOnFirstPollAndExportShortDiagnostics`, `candidateSnapshotKeepsPollingInsteadOfSkipping`; timeout/network mevcut testleri. |
| `QueueEngine` / `run()` | Önceki bayrağa göre atlar/ilerlerdi. | **Kod değişmedi**; explicit text skip bir kez kaydedilir, sonraki profil işlenir. | `QueueReliabilityTest.explicitTextPolicySkipsOnceAndImmediatelyContinues`. |
| `version.properties` | VERSION_NAME=5.0.0, VERSION_CODE=8. | VERSION_NAME=5.0.0, VERSION_CODE=9. | Manifest ve release gate testleri; APK metadata doğrulaması. |
| `tools/release/release_gate_500.py` / `check_manifest()`, `verify()` | 5.0.0 build 8'e sabitti. | Yalnızca release 5.0.0 build 9 kabul edilir; debug/yanlış build/yanlış imza reddedilir. | `tools/release/tests/test_release_gate_500.py`. |
| `tools/release/build9_evidence.py` / `collect()` (yeni) | Bu build için kanıt paketleyicisi yoktu. | Gerçek JUnit toplamlarını ve testte export edilmiş diagnostics'i alır; boş/başarısız kanıtı reddeder. Device/live sonuç uydurmaz. | `tools/release/tests/test_build9_evidence.py`. |
| `.github/workflows/android.yml` | Yeni classification fixture dosyası çalıştırılmıyordu. | Mevcut Chromium adımında yeni classification testi de çalışır ve raporlanır. | Offline test komutunun yerel çalışması; CI kontrolü. |
| `.github/workflows/release-fetcher-build9.yml` (yeni) | Eski publisher build 8/presigned APK'ya sabitti. | Güncel kaynaktan test, eski anahtarla imza, eski APK sertifikasıyla karşılaştırma, draft indirme/hash doğrulaması ve yeni build release'i. | Release gate unit testleri, YAML parse ve gerçek CI koşusu. |

`FailurePolicy`, ağ hataları, WEBVIEW_TIMEOUT, cookie/challenge kodları, queue
şeması ve mevcut indeks mimarisi bu build için değiştirilmedi. Önceki yerel
paketlerin değişiklikleri kaynak geçmişinde korunur.

## Test ve diagnostics kanıtı

Kanıt düzeyi **CODE_REVIEW + UNIT_TEST / offline Chromium fixture**.
Yerel tarayıcı koşusunda **35** fixture sayfası geçmiştir. Release kapısı ve
kanıt paketleme testleri **23/23** geçmiştir. Tam Android birim test paketi **180/180** geçti; `lintDebug` ve `lintRelease`
**0 hata, 32 uyarı** ile başarılıdır. Release/debug ve instrumentation APK
derlemeleri başarılıdır. Ham kanıtlar release `test-evidence.json` ve test
raporlarında yer alır.

`diagnostics-offline-fixture.txt`, `Diagnostics.export()` tarafından testte
üretilen çıktıdır. Açıkça **OFFLINE UNIT TEST FIXTURE** diye işaretlidir. Metin
fixture'ları ilk poll'de tamamlanır; sahte zamanlayıcıda `elapsedMs=0` görülür.
Bu değer gerçek hesap/sayfa yükleme süresi ölçümü değildir. Fiziksel cihaz veya
canlı Instagram testi yapılmadı; belirli bir hesabın birkaç saniyede atlandığı
iddia edilmez. Cihazdan yeni diagnostics için güncelleme sonrası mevcut
Diagnostics Export kullanılmalıdır.

## Release

Yeni yayın etiketi: `v5.0.0-build.9`; APK içindeki versionName **5.0.0**, build **9**.
Önceki `v5.0.0` release/tag/APK korunur. Yayın akışı yeni anahtar üretmez ve
unsigned/debug APK'yı release olarak yayımlamaz. Orijinal imzalama yapılandırması
bulunmazsa yayın durur. Önceki yayın APK'sının doğrulanmış sertifika SHA-256'sı:

`a3058fab8d03c1549abb04ae4660ff2256296967e6291bd7a751ecb9b32e82c2`

İmzalı candidate aynı sertifika, com.instagram.insfav, 5.0.0, build 9 ve
non-debuggable denetiminden geçer; yayımdan önce draft APK yeniden indirilip
SHA-256 karşılaştırılır. Gerçek yayın sonucu workflow durumundan doğrulanmalıdır.
