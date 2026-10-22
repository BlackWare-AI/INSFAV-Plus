# INSFAV 5.0.0 / build 10 — profil geçmişi ve canlı işlem durumu

Bu değişiklik Fetcher kuyruğu ve Fetcher ekranıyla sınırlıdır. Rescan, thumbnail görüntüleme, SAF indeksi ve 128/256 WebP biçimleri değişmez. Thumbnail dosyaları silinmez, toplu doğrulanmaz veya yeniden indirilmez.

## Bulgular ve karar

Ekran görüntüsündeki beş kayıt `RETRY_LATER`; `SKIPPED_UNAVAILABLE` değildir. Görüntü gerçek hata kodunu veya aktif worker sayısını göstermediğinden beş istek sonrasındaki cihaz duraksamasının kök nedenini kanıtlamaz. Kaynakta beş hesaplık durdurma sınırı bulunmadı. Worker başlangıç aralığı, 45 saniyelik profil yükleme süresi ve global oturum/rate-limit beklemeleri vardı. Bunların sayısal sınırları değiştirilmedi.

Önceki davranış: `RETRY_LATER` ve attempts<3 olan profiller otomatik olarak yeniden istenirdi. Start Missing/Resume mevcut atlamaları korurdu; ancak JSON'dan çıkartılan hesap satırı silindiği için hesap tekrar eklenince geçmiş kaybolurdu.

Yeni kullanıcı talebi doğrultusunda: tamamlanan hesap bazlı hatalar (`FAILED`, `SKIPPED_*`, deneme yapılmış `RETRY_LATER`) yalnızca **Retry Skipped / Failed Profiles** ile yeniden istenir. Bekleyen yeni hesaplar işlemeye devam eder. Timeout hâlâ `WEBVIEW_TIMEOUT` / `RETRY_LATER`; kapanmış hesap olarak yorumlanmaz. İşlem kesintisi ve geçici oturum doğrulama beklemesi tamamlanmış profil hatası sayılmaz; bu durumlarda kaldığı yerden devam korunur. Geçersiz oturum, challenge ve gerçek rate limit yine güvenli biçimde durdurur.

## Dosya / metot / doğrulama

| Dosya, metot | Eski davranış → yeni davranış | Test | Kanıt |
|---|---|---|---|
| `QueueStore.java`, `createCatalog`, `onUpgrade` | SQLite v4 → v5, ek `fetch_outcomes` tablosu. Eski başarısız kayıtlar deneme sayılarıyla aktarılır; mevcut tablolar ve dosyalar korunur. | `ProfileOutcomeHistoryTest.versionFourUpgradeImportsHistoryWithoutResettingStatusesAttemptsOrImageIndex` | CODE_REVIEW, UNIT_TEST |
| `QueueStore.java`, `sync`, `mark` | JSON'dan çıkarılan hesap geçmişi kaybolurdu → küçük özel SQLite kaydı hesap tekrar eklendiğinde geri yüklenir. Yeni hesaplar etkilenmez. Hesap durumu ve kayıt tek transaction içinde yazılır. | `allProfileFailuresSurviveReopenStartResumeAndJsonRemovalWithoutHidingNewUsers`, `ledgerWriteFailureRollsBackAccountStatusInsteadOfLosingHistory` | UNIT_TEST |
| `QueueStore.java`, `eligible`, `pending`, `next`, `claim`, `retrySkipped` | Otomatik üç deneme → tamamlanmış başarısız istek için açık Retry gerekir. Retry yalnızca mevcut listedeki başarısız hesapları açar; başarılar ve listede bulunmayan geçmiş korunur. | `ProfileOutcomeHistoryTest`, `IntegrationQueueTest`, `PoolTest.skippedRetryIsExplicitAndSurvivesDatabaseReopen` | UNIT_TEST |
| `QueueEngine.java`, `run` | Otomatik başarısız hesap döngüleri ve yalnızca terminal sonuçlara göre hız → profil hatası kaydedilir, diğer hesaplar sürer; işlenen istekler hız ve çalışma sayacına girer. Sonuç ve bekleme nedeni yayımlanır. | `QueueReliabilityTest.fiveWorkerTimeoutsDoNotStopTheNextProfilesOrRepeatOnResume`, `skippedProfileIsRememberedAcrossJsonRemovalAndReadditionWhileNewAccountRuns`, `IntegratedEngineTest` | UNIT_TEST |
| `FetchBroker.java`, `nextDispatchAt`; yeni `FetchProgress.java`, `snapshot`; `QueueControl.java` | Worker beklemesinin geri sayımı görünmezdi → aktif/queued işlem, son sonuç ve bir sonraki uygun başlangıç zamanı ayrı gösterilir. Worker ayarı ve pacing değişmez. | `BrokerTest.fiveWorkersExposeAndFinishTheirConfiguredWaitWithoutAnAttemptLimit`, `FetchProgressTest` | UNIT_TEST |
| `Fetcher.java`, `snapshot` | Yalnızca genel sayaçlar → `process`, `held`, `recordedOutcomes` ek alanları. Eski snapshot alanları korunur. | Kuyruk entegrasyon testleri ve `tests/browser.cjs` native bridge fixture | UNIT_TEST |
| `Diagnostics.java`, `export` | Genel durumlar → özel geçmişin yalnızca sayısı ve Retry bekleyen sayısı eklenir. Kullanıcı adı, URL, cookie veya HTML dışa aktarılmaz. | `DiagnosticsTest.privateOutcomeLedgerExportsOnlyCountsAndCodes` | UNIT_TEST |
| `web/app.js`, `updateFetcher`; `web/style.css`; `assets/index.html` ve üretilen JS/CSS/map dosyaları | Sayaçlarla eylemler arasındaki boşluk → mevcut koyu kart stiliyle Current Process, aktif/queued sayısı, son sonuç, geri sayım, ilerleme, süre ve kayıt açıklaması. | `tests/browser.cjs`: 280/393/600 px yerleşim, bekleme→aktif geçişi, held ilerleme hesabı | UNIT_TEST (Chromium fixture) |
| `FailurePolicy.java` açıklaması; `docs/FETCHER-INTEGRATION.md`, `docs/FETCHER-040.md`; `version.properties` | Eski otomatik deneme açıklaması güncellenir. Görünen sürüm 5.0.0, build 10. FailurePolicy karar kodları değişmez. | Android build ve lint, mevcut policy testleri | CODE_REVIEW, UNIT_TEST |

## Beklenen kontroller

- Beş worker'ın ilk beş timeout'u sonrası yedi yeni hesap tamamlanır; otomatik eski profil tekrarı yoktur.
- Start Missing ve Resume aynı 12 hesaptan başarısız beşine yeni istek/SAF lookup göndermez. Kullanıcı Retry deyince yalnızca bu beş hesap yeniden istenir.
- JSON'dan çıkarılıp yeniden eklenen erişilemeyen hesap atlanırken yeni hesap indirilir.
- SQLite v4'ten aktarım ve yeniden açma; ledger yazma hatasında transaction rollback.
- Memory, SQLite, SAF izin, session ve 429 korumaları mevcut testlerle korunur.

## Sınırlamalar

Canlı Instagram ve kullanıcının telefonu bu çalışmada erişilebilir değildir. Beş isteklik cihaz duraksamasının kesin kök nedeni teşhis edilmiş sayılmaz; yeni kart ve diagnostics gerçek beklemeyi gösterebilir. Çevrimdışı testlerde beş hata sonrası devam doğrulanır. Android 14 CI emülatörü de çevrimdışı fixture kullanır. Playwright ilk çalıştırmada varsayılan tarayıcı yolu bulunamadı; sistem Chromium yolu ile tekrar çalıştırılır. Testler atlanmadı veya etkisizleştirilmedi.

Tamamlanmış geçmiş, uygulamanın özel SQLite dosyasında tutulur; kişisel kimlikler diagnostics'e girmez. Uygulama verileri temizlenirse/kaldırılırsa Android bu özel kaydı siler. Build 9 ile aynı imza kullanıldığından imza nedeniyle kaldırma gerekmez; gerçek cihazda build 9→10 yerinde yükseltme ayrıca test edilmelidir.

Yerel son doğrulama: **213 Android/Robolectric testi**, hata/başarısız/atlanan 0; **118 Chromium UI kontrolü**; **35 DOM sınıflandırma fixture'ı**; **11 JS çekirdek testi**. `./gradlew :app:testDebugUnitTest`, `./gradlew :app:lintDebug`, release/debug/androidTest derlemeleri geçti. Lint: Error/Fatal 0, mevcut 31 uyarı. Fiziksel cihaz ve canlı Instagram testi yapılmadı.
