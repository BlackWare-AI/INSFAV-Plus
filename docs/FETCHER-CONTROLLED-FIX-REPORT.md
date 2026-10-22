# Thumbnail Fetcher: kontrollü tanılama ve düzeltme

> Bu rapor build 8 yerel paketlerinin tarihsel kaydıdır. Build 9 için kullanıcının
> istediği metin temelli hızlı atlama politikası bazı karar/test koşullarını değiştirir.
> Güncel değişiklik ve kanıt için [Build 9 raporuna](FETCHER-BUILD9.md) bakın.

Kaynak: BlackWare-AI/INSFAV `main`, `4d442c5cd52f5edcdfdc35e7bb2701912c5afda2`.
Kapsam: ilk iki çalışma paketi (0–6). Normal görüntüleme, SAF genel indeksi,
Rescan Thumbnails, JSON formatı ve favori klasörleri kapsam dışındadır.

## Adım 0 — Değişiklik öncesi kaynak incelemesi

Java dosyalarının gerçek kökü `app/src/main/java/com/instagram/insfav/fetcher/`.
Probe: `app/src/main/assets/profile-probe.js`.

| Dosya / metot | Referans commit'teki davranış | Kanıt |
|---|---|---|
| `profile-probe.js` / ana fonksiyon, `walk()` | Görünür unavailable metni ve `rootView.resource.__dr == PolarisErrorRoot.react`, görsel aranmadan `PROFILE_PAGE_UNAVAILABLE` üretir. Canonical yoksa location kullanılır. | CODE_REVIEW |
| `WebViewProfileProvider` / `Operation.poll()` | Tek wall snapshot'ı (HTTP ölçülmeden 200 eklenerek) tamamlanır. 45 s sonunda profil için `PROFILE_IMAGE_URL_MISSING`, auth için `COOKIE_AUTH_UNVERIFIED`. | CODE_REVIEW |
| `WebViewProfileProvider` / `Operation.start()`, `resolve()` | Profil için 55 s watchdog, 65 s await; auth için 18 s watchdog, 25 s await. Poll 500 ms; bu inceleme gerçek zamanlama çakışmasını kanıtlamaz. | CODE_REVIEW |
| `WebViewProfileProvider` / `applySessionCookies()`, `authenticate()` | Callback false ise `COOKIE_WEBVIEW_REJECTED`. Başarılı aktarım yalnızca `SHARED_COOKIES_APPLIED`. Authenticate boş kullanıcıyla viewer kimliği arar. | CODE_REVIEW |
| `FailurePolicy` / `unavailable()`, `noImage()`, `pause()` | Unavailable -> SKIPPED_UNAVAILABLE; missing URL, identity ve ambiguity -> SKIPPED_NO_IMAGE. WEBVIEW_TIMEOUT global barrier değildir. | CODE_REVIEW |
| `Fetcher` / `action()`, `sync()` | Kuyruk ve kalıcı metadata hazırlanır; batch için authenticate çağrılmaz. | CODE_REVIEW |
| `FetchBroker` / `loop()`, `execute()`, `block()` | Provider doğrudan fetch'e gider. Her COOKIE_ hatası sessionEvidence=INVALID yapar; auth kanıtı ile setup hatası karışır. RuntimeException profil koduna çevrilir; OOM yalnızca concurrency azaltır. | CODE_REVIEW |
| `QueueEngine` / `run()` | Per-profile commit RuntimeException dış catch'e çıkarak bütün kuyruğu PROCESSING_ERROR ile durdurur. IOException profil hatası sayılır. Dış catch OOM'u da genel işlem hatası olarak yutar. | CODE_REVIEW |
| `DownloadWorker` / `doWork()` | Bütün Exception'lar SYSTEM_RESTRICTED olur. | CODE_REVIEW |
| `QueueStore` / `prepareMissing()`, `sync()`, `recoverInterrupted()` | SQLite v4 kalıcı indeks. Start Missing FAILED/RETRY_LATER deneme geçmişini sıfırlar. Sync kullanıcıları normalize ve dedupe eder; çıkarılan hesap satırını siler, thumbnail silmez. | CODE_REVIEW |
| `RunPolicy` / `contains()`, `nextWindow()` | Varsayılan 02:00–07:00; gece yarısını aşan aralık desteği var. | CODE_REVIEW |
| `ConcurrencyPolicy` / `initial()`, `budget()`, `reduced()` | Kullanıcı ayarı ve tahmini bellek bütçesiyle admission; bu rakamlar ölçülmüş WebView maliyeti değildir. | CODE_REVIEW |

Kullanıcının önceki diagnostics sayıları DIAGNOSTICS girdisidir; asıl dosya bu
çalışmada verilmedi. HTTP 200 ne erişilebilirliği ne kapanmayı kanıtlar.
UNKNOWN, diagnostics içinde doğrulama kanıtı bulunmadığını belirtir.
PolarisErrorRoot'un tek başına kapanmış hesap anlamına geldiğine dair kanıt yoktur.

## Paket 1 — Tanılama

- `profile-probe.js`: sabit `reason`, `pageReady`, `candidateFound`, `searchCompleted` alanları.
- `WebViewProfileProvider.Operation.poll()/finish()/client()`: alt kod, gerçekten
  bilinen HTTP durumu (0=bilinmiyor), yükleme/görsel/session bilgisi ve süre.
- `Diagnostics.outcome()/session()/export()`: safe alanları dışa aktarır;
  bilinmeyen alt kod UNKNOWN_RESPONSE olur. DOM, HTML, profil adı, URL ve cookie yazılmaz.
- `FetchBroker.beginBatch()/submit()/loop()`, `QueueControl`: sayısal batch ve
  kuyruk metrikleri. SESSION olayları batch numarası taşır. Çerez uygulama auth değildir.
- Testler: `DiagnosticsTest.structuredOutcomesAndBatchSessionEventsSurviveExportWithoutProbePayload`,
  `tests/probe-classification.cjs` (tamamen intercept edilen offline DOM fixture'ları).

İlk Gradle denemeleri ortam eksikleri (proxy ve JDK compiler) nedeniyle durdu;
bunlar test assertion sonucu değildir. Nihai test sonuçları aşağıya eklenecektir.

Paket 1 sonucu: DiagnosticsTest **2/2 geçti**; offline DOM **5/5 geçti**;
`:app:lintDebug` başarılı. Henüz DEVICE_TEST veya gerçek Instagram kanıtı yok.

## Paket 2 — Değişiklik ve kanıt matrisi

Aşağıdaki test adları `app/src/test/java/com/instagram/insfav/fetcher/`
altındadır. Tarayıcı fixture'ları `tests/probe-classification.cjs` içindedir.
Her satırın sonucu nihai tam test koşusuna dahildir. Kanıt **CODE_REVIEW +
UNIT_TEST** düzeyindedir; canlı Instagram doğrulaması anlamına gelmez.

| Dosya / metot | Eski davranış | Yeni davranış | İlgili test / sonuç | Sınırlama |
|---|---|---|---|---|
| `profile-probe.js` / ana fonksiyon, `walk()` | Text/ErrorRoot görsel aramasını keserdi. | Kimliğe bağlı görsel aranır; geçerli aday hata root/metni nedeniyle elenmez. Bulunamayan sayfanın metin ve route kanıtı ayrı döner. | `probe-classification.cjs`: geçerli görselle root/metin, login, geç yükleme, 200/404; **geçti**. | Offline Chromium DOM; gerçek Instagram değil. Canonical seçim politikasının ayrı optimizasyonu ertelendi; mevcut kullanıcıya bağlı header davranışı korundu. |
| `ProbeDecision` / `failure()`, `deadline()` (yeni) | İlk wall sonucu kalıcı atlanabiliyordu. | Kalıcı unavailable: doğrulanmış batch oturumu + gerçek main-frame HTTP 404 + açık görünür unavailable metni + tamamlanmış yükleme/arama + hiçbir görsel adayı görülmemesi. HTTP 200 veya ErrorRoot tek başına retry. | `ProbeDecisionTest.repeated200ErrorSnapshotsNeverBecomeIndependentConfirmation`, `main404NeedsExplicitTextCompletedSearchAndVerifiedSession`, `defaultAvatarNeedsExplicitIdentityAndCompleteSearch`; **geçti**. | 404+metin, o istekte erişilemezlik kanıtıdır; hesabın silindiğine dair tarihsel/kalıcı olgu iddiası değildir. |
| `WebViewProfileProvider.Operation` / `poll()`, `armWatchdog()`, `finish()` | 45 s -> missing URL / no-image; geç callback deadline'ı aşabilirdi. Cleanup hatası asıl sonucu değiştirirdi. | Poll deadline, geç JS callback ve watchdog -> WEBVIEW_TIMEOUT. Cleanup hatası asıl timeout'u korur; hasarlı session tekrar kullanılmaz. Script OOM -> kontrollü memory barrier. | `ProviderOperationTest.deadlineAndDelayedJsCallbackCannotBecomeNoImageOrSuccess`, `lateLoadingImageSucceedsBeforeDeadline`, `stalledJsCallbackIsStoppedByOperationWatchdog`, `cleanupErrorCannotRewriteDeadlineAsAnotherProfileResult`, `transientNetworkFailureAndScriptMemoryLimitAreNotPermanentAbsence`; **geçti**. | 500 ms poll, 45/55/65 s sınırları korunur. Gerçek ana thread gecikmesi ölçülmedi. OOM testi enjekte edilmiş Error'dur; gerçek heap tükenmesi değildir. |
| `WebViewProfileProvider.Operation` / `client()`, `poll()` | Tek HTTP 404 hemen unavailable; DOM wall'larına ölçülmemiş 200 yazılırdı. | Main 404 DOM kanıtını bekler; image/subresource 404 hesap kapanması değildir. Bilinmeyen HTTP 0'dır; hata callback'inin gerçek status'u korunur. | `ProviderOperationTest.actualMain404WaitsForIndependentDomEvidence`, `http404AloneDoesNotOverrideValidImageAndImage404IsNotProfileDeletion`, `repeatedErrorPage200IsRetryableAndPausePreservesNextOperation`; **geçti**. | Android normal başarılı navigasyonun HTTP status'unu bu API'de bildirmez; 0'dan 200 çıkarımı yapılmaz. |
| `WebViewProfileProvider` / `authenticate()`, `applySessionCookies()` | Transfer tamamlanmasıyla doğrulama ayrı işlevlerdi ama batch authenticate çağırmazdı. Async setCookie RuntimeException için tamamlanma güvencesi yoktu. | Transfer/reuse, auth başlangıcı ve doğrulama sonucu ayrı SESSION olaylarıdır. Yalnızca callback false -> COOKIE_WEBVIEW_REJECTED. Platform exception -> WEBVIEW_UNAVAILABLE. | `CookieApplicationTest.acceptedCookiesOnlyCompleteTransferAndDoNotVerifySession`, `rejectedCallbackAndPlatformFailureHaveDifferentCodes`, `CookieTest.cookieSetupAndTransferDoNotProveSessionValidity`; **geçti**. | Callback kabulü sadece WebView cookie store kabulüdür. Session kimliği için mevcut ds_user_id ile viewer eşleşmesi gerekir. |
| `WebProfilePolicy` / `http()` | Alt kaynak 401 de cookie expired sayılırdı. | Main 401 session gate olarak kalır; alt kaynak 401 ACCESS_BLOCKED olur, cookie süresinin bittiği ileri sürülmez. | `WebProfileTest.ajaxRateLimitAndSessionFailuresPauseButImage404IsNotProfileDeletion`; **geçti**. | Alt kaynak erişim engeli güvenli ortak pause oluşturabilir; hesabın erişilemezliğine dönüşmez. |
| `ProfileProvider` / `authenticate()`, `Failure` | Varsayılan auth bilinmeden HTTP 200 ve pause taşırdı; unavailable kanıtı için alan yoktu. | Varsayılan auth HTTP 0 / belirsiz; yalnızca çalışma zamanında kullanılan confirmedUnavailable bayrağı. SQLite/JSON şeması değişmez. | `ProbeDecisionTest`, `BrokerTest.resumeReplacesInconclusiveSessionWithoutMarkingCookiesInvalid`; **geçti**. | Kanıt bayrağı iç API'dir; eski queue kayıtları yeniden yorumlanmaz. |
| `FailurePolicy` / `unavailable()`, `noImage()`, `retry()`, `pause()`, `sessionTransient()` | Missing URL, ambiguity ve identity belirsizliği kalıcı no-image olabiliyordu. | Yalnızca açık default avatar no-image olur. Unconfirmed unavailable, missing URL, ambiguity, timeout ve ağ hataları retry. IMAGE_MEMORY_LIMIT kuyruk pause. | `ProbeDecisionTest`, `IntegrationQueueTest.missingUrlIsRetryNotDeletedAndCannotBlockWholeQueue`, `QueueReliabilityTest.timeoutRetriesAreBoundedAndFollowingProfileSucceeds`; **geçti**. | Yeni belirsiz hatalar mevcut 3 deneme sınırına tabidir; tükenen RETRY_LATER kayıtları silinmez. |
| `FetchBroker` / `beginBatch()`, `authenticateBatch()`, `cancel()`, `loop()`, `execute()`, `block()` | Batch doğrudan fetch'e giderdi; OOM yalnızca limit azaltırdı. | Profil dispatch'inden önce batch başına auth. Belirsiz/network auth: 60 s retryAt, UNKNOWN, hesap deneme bütçesini tüketmeden bekleme. Doğrulanmış geçersiz oturum: `Paused: session not verified`. Auth sırasında Pause iptal eder. OOM yeni işi durdurur ve session kaynaklarını iptal eder. | `BrokerTest.eachBatchAuthenticatesBeforeProfilesEvenWithReusedProvider`, `pausingDuringAuthenticationCannotStartProfileAndResumeReverifies`, `resumeReplacesInconclusiveSessionWithoutMarkingCookiesInvalid`; `QueueReliabilityTest.invalidSessionStopsBeforeAnyProfileAndPreservesQueue`, `temporaryAuthNetworkFailureWaitsThenReauthenticatesOnResume`, `memoryPausePreservesSuccessAndResumeDoesNotDownloadItAgain`; **geçti**. | Auth fixture'dır. Kullanıcının concurrentWebViews tercihi değişmez. Otomatik auth retry için mevcut background/armed politikası gerekir; aksi halde cooldown sonrası manuel Resume. |
| `SessionState` / `evidence()`, `invalid()`, `pausedText()` | Her COOKIE_ hatası INVALID olabiliyordu. | VERIFIED / UNVERIFIED / UNKNOWN ayrımı; eski INVALID gösterimde UNVERIFIED olur. Setup hatası session geçersizliği değildir. | `CookieTest.cookieSetupAndTransferDoNotProveSessionValidity`, `QueueReliabilityTest` auth testleri; **geçti**. | UNKNOWN doğrulamanın başarısızlığını değil kanıt yokluğunu gösterir. |
| `QueueEngine` / `run()` | Coordinator profil RuntimeException'ı tüm kuyruğu durdururdu. Depolama IOException profil başarısızlığı sayılabilirdi. | Profil RuntimeException -> yalnızca PROFILE_PROCESSING_ERROR. SAF, SQLite ve genel storage hataları kuyruk seviyesinde pause. OOM ayrı memory pause. Retry'ler ayrı korunur; auth beklemesi hesap deneme sayısını artırmaz. Çıkışta kalan claim'ler kalıcı RETRY_LATER olur. | `QueueReliabilityTest.providerRuntimeFailureAffectsOnlyOneProfile`, `coordinatorRuntimeFailureDoesNotStopFollowingProfile`, `storageCommitFailurePausesWithoutConsumingProfileRetryBudget`, `storagePermissionLossStaysQueueLevel`, `databaseErrorStaysQueueLevel`, `coordinatorMemoryErrorStopsNewProfilesAndKeepsCommittedFile`; **geçti**. | SQLite hatası enjekte edilmiştir; fiziksel DB bozulması veya gerçek disk dolması testi değildir. OOM altında persist/cleanup mümkün olduğu ölçüde yapılabilir. |
| `QueueStore` / `prepareMissing()`, `deferClaims()` (yeni) | Start Missing FAILED/RETRY_LATER deneme geçmişini sıfırlardı. | Geçmiş korunur; sadece claim'ler güvenle beklemeye alınır. Mevcut explicit retry_skipped eylemi değiştirilmez ve bu görevde çağrılmaz. | `IntegrationQueueTest.startMissingResumeAndJsonDeltaPreserveAllHistoryAndImageMetadata`, `deduplicatesAndPreservesDurableStatuses`; `FetcherCatalogTest` warm/reopen/migration testleri; **geçti**. | DB v4, dosya adlandırma ve silme politikası aynıdır. JSON'dan çıkarılan hesap satırı mevcut politika ile çıkarılır; image kaydı/dosya silinmez. |
| `DownloadWorker` / `doWork()`, `failureCode()` (yeni) | Her Exception SYSTEM_RESTRICTED olurdu. | Foreground restriction, storage permission, database ve diğer worker hataları ayrılır. InterruptedException retry + interrupt korunması. | `DownloadWorkerFailureTest.onlyForegroundRestrictionsAreSystemRestricted`; **geçti**. | Gerçek Android foreground kısıtlaması cihazda denenmedi. |
| `Fetcher` / `action()` | RetryAt kontrolünün mesajı sadece rate limit derdi. | Auth beklemesini de kapsayan `Retry cooldown is still active.` mesajı; mevcut cooldown kontrolü korunur. | `QueueReliabilityTest.temporaryAuthNetworkFailureWaitsThenReauthenticatesOnResume`; **geçti**. | Diğer uygulama akışları veya UI kaynakları değiştirilmedi. |
| `Diagnostics` / `outcome()`, `reason()`, `session()`, `export()`; `QueueControl.queuedAccounts` | Kod + tek sayısal detay vardı. | Sabit alt kodlar; HTTP, yükleme, aday, session, süre/timeout, worker ve kuyruk alanları; sayısal batch. | `DiagnosticsTest` **2/2 geçti**, `ProviderOperationTest` sonuç testleri. | Dönen DOM snapshot'ları uygulama içinde görsel çözmek için kullanılır; dosyaya/export'a kullanıcı adı, URL, cookie veya HTML yazılmaz. |
| `PoolInstrumentation` / matrix fixture, `background()`, `viewerFixture()` (test kodu) | Auth isteği yoktu; her navigasyon profil sayılırdı. | Yeni zorunlu auth için offline viewer fixture; auth navigasyonu profil overlap/sayaçlarına dahil edilmez. | `:app:assembleDebugAndroidTest` derleme kontrolü. | Bu oturumda emülatör/cihaz üzerinde çalıştırılmadı; DEVICE_TEST kanıtı yok. |

## İndeks ve veri koruma

`FetcherCatalog`, `OutputFolder`, genel INSFAV thumbnail/SAF indeksi, JSON import,
normal görüntüleme, favori klasörleri ve rescan kodu değiştirilmedi. Testler
geçici, sentetik dosyalar oluşturur; kullanıcının thumbnail klasörüne erişilmedi.
Yeni bağımlılık yoktur. SQLite sürümü 4 ve mevcut 128×128 / 256×256 WebP
uyumluluğu korunur.

`FetcherCatalogTest.thirteenThousandFiveHundredFilesAreIndexedOnceAndWarmStartResumeNeedNoListingOpenOrDecode`
13.500 sentetik WebP üzerinde ilk tam indeksi ve sonraki artımlı başlangıçları
ayrı ölçer. İlk indeks bir listeleme ve 27.000 open/decode yapar (iki decode
geçişi mevcut davranıştır); sıcak başlangıç/Resume bunların hiçbirini yapmaz.
`QueueReliabilityTest.memoryPausePreservesSuccessAndResumeDoesNotDownloadItAgain`
ise başarılı dosyanın byte'larının korunmasını, tekrar indirilmemesini ve
Resume'da tam tarama yerine yalnızca kalan profillerin exact-name sorgularını
kontrol eder. Exact-name sorgusu SAF sağlayıcısının selection desteğine bağlıdır;
sağlayıcının bunu nasıl uyguladığı cihazda ölçülmedi.

Yeni Start Missing, SUCCESS, EXISTING, FAILED, SKIPPED ve RETRY_LATER geçmişini
korur. JSON dedupe ve ekleme/çıkarma testinde image metadata korunur. Kuyruk DB'si
yeniden açıldığında devam testleri geçer. Bu paket eski SKIPPED_UNAVAILABLE
kayıtlarını topluca silmez, sıfırlamaz veya yeniden kuyruğa almaz.

## Başarısız denemelerin kaydı

- İlk build girişimleri: proxy ayarı olmadan bağlantı reddi; ardından ortamda
  compiler bulunmaması. Java 17 JDK ve SDK 35 ortam dışından kurularak düzeltildi.
- İlk DOM diagnostic fixture'ı: UTF-8 charset belirtilmeyen fixture yanıtında
  unavailable metni yanlış decode oldu. Fixture content-type düzeltildi; assertion korunur.
- İlk watchdog testi: Robolectric altında `Operation.start()` kurulumu
  WEBVIEW_UNAVAILABLE döndü. Hangi Android API'sinin buna neden olduğu ayrıca
  izole edilmedi; bunun zamanlayıcı hatası olduğu kanıtlanmadı. Üretimde aynı
  şekilde çağrılan `armWatchdog()` timer'ı izole edilerek test edilir; beklenen
  timeout assertion'ı değiştirilmedi.
- İlk Fetcher test turunda 118 testten 1'i başarısızdı: Resume testi tüm
  childQueries sayılarını tam tarama saymıştı (4 -> 7). Ayrı fullListings ve
  targetedLookups sayaçları eklendi. Test hâlâ **tam tarama sıfır**, kalan iki
  profil için **3 exact-name sorgu** ve başarılı dosyanın aynı byte'ları
  koşullarını denetler. Hiçbir test kaldırılmadı, ignore edilmedi veya boşaltılmadı.
- Decoder'ın negatif fixture'larda stderr'e yazdığı `Failed to create image
  decoder ... unimplemented` satırları saklandı; JUnit sonucu ayrıca kontrol edildi.

## Nihai doğrulama

Ortam: Java 17, Android SDK 35. Proxy/JDK/SDK ve Gradle test heap ayarları
çalışma ortamında yapıldı; depo bağımlılıkları veya uygulama worker tercihi
bu amaçla değiştirilmedi.

| Kontrol | Sonuç | Kanıt düzeyi |
|---|---|---|
| `./gradlew :app:testDebugUnitTest` | **176 test, 0 failure, 0 error, 0 skipped** | UNIT_TEST |
| `./gradlew :app:lintDebug` | **Başarılı; 0 hata, 32 uyarı** | CODE_REVIEW / static analysis |
| `:app:assembleDebug :app:assembleDebugAndroidTest` | İki APK derlendi | Build kontrolü; DEVICE_TEST değildir |
| `tests/probe-classification.cjs` | **14 offline DOM senaryosu geçti** | UNIT_TEST / browser fixture |
| `tests/probe-browser.cjs` | Thumbnail/HD probe regresyonu geçti; tüm istekler intercept | UNIT_TEST / browser fixture |
| `node --test tests/core.test.cjs` | **Web core regresyonları geçti** | UNIT_TEST |
| `git diff --check` | Temiz | CODE_REVIEW |
| ADB | Bağlı cihaz/emülatör yok; `/dev/kvm` yok | DEVICE_TEST yapılmadı |
| Gerçek Instagram | İstek, cookie veya gerçek hesapla test yapılmadı | Canlı doğrulama yok |

Son 13.500 WebP fixture koşusu:

| İşlem | Süre | Tam listeleme | Dosya açma | Decode |
|---|---:|---:|---:|---:|
| İlk tam indeks | **4.038 ms** | 1 | 27.000 | 27.000 |
| Sıcak Start Missing | **20 ms** | 0 | 0 | 0 |
| Sonraki iki Resume/reopen | Ayrı süre raporlanmıyor | 0 | 0 | 0 |

Bu zamanlar tek fixture koşusunun ölçümüdür; cihaz/SAF sağlayıcısı/Instagram hız
öngörüsü değildir. İlk tanılama paketindeki lint 26 uyarıydı. Son paketteki
6 ek uyarı `ApplySharedPref` önerisidir: yeni durable gate/pause güncellemeleri
worker/coordinator thread'inde `commit()` kullanır. Uyarılar bastırılmadı;
ham lint raporu teslim paketindedir. Mevcut diğer alt sistem uyarıları bu
kapsamda değiştirilmedi.

## Diagnostics teslimi ve cihazda üretme

Teslim paketindeki `diagnostics-offline-fixture.txt`, gerçekten `Diagnostics.export()`
ile birim testinde üretilmiştir. Başlığı açıkça OFFLINE UNIT TEST FIXTURE der;
cihaz diagnostics'i veya canlı Instagram sonucu değildir. Kullanıcının önceki
3.403 hesaplık gerçek çalışma verisi bu dosyaya karıştırılmamıştır.

Yeni alanlar:

- `code`: ana sonuç; `reason`: sekiz sabit alt koddan biri.
- `http`: gerçekten bilinen HTTP durumu; **0 = bilinmiyor**.
- `pageFinished`: Android onPageFinished görüldü; bu tek başına profil/görsel hazır demek değildir.
- `candidateFound`: işlemde aday görüldü; geçerli ve yüklenmiş görsel olduğu anlamına gelmez.
- `session`: VERIFIED / UNVERIFIED / UNKNOWN.
- `elapsedMs`, `timeoutMs`: işlem süresi ve poll deadline bütçesi; mevcut watchdog ayrıca 55 s (auth 18 s) kalır.
- `workers`, `effectiveWorkers`, `activeAccounts`, `queuedAccounts`, `queue`, `batch`: güvenli sayılar ve sabit queue durumu.
- `SESSION SHARED_COOKIES_APPLIED <batch>`: yalnızca transfer kabulü.
  `AUTHENTICATION_STARTED`, `SESSION_VERIFIED`, `SESSION_UNVERIFIED`,
  `SESSION_UNKNOWN` ayrı olaylardır. Lease yeniden kullanılırsa SHARED_COOKIES_REUSED yazılır.

Cihaz doğrulaması için mevcut uygulamanın verisi ve imzası korunarak bu kaynak
sürümünden güncelleme üretilmelidir. Teslim edilen APK **debug** imzalıdır;
mevcut release kurulumuyla imza uyumluluğu doğrulanmadı. Veriyi korumak için
mevcut uygulama kaldırılmamalıdır; mevcut verili kurulum üzerine güncelleme aynı
imza anahtarıyla üretilmelidir.

Mevcut worker sayısı ve çalışma saatleriyle, tanımlı çalışma penceresi içinde:

1. Bekleyen işler için Start Missing veya Resume çalıştırın; otomatik eski skipped retry başlatmayın.
2. Kısa bir örnekleme sırasında session olayları ve birkaç profil sonucu oluşmasını bekleyin.
3. Pause, ardından Resume ve tekrar Pause yapın.
4. Mevcut Diagnostics Export eyleminden dosyayı dışa aktarın.
5. Sonraki değerlendirmede yeni dosyanın alt kod dağılımını, auth olaylarını,
   timeout'ları ve sıcak başlangıç listeleme/open/decode sayaçlarını karşılaştırın.

Bu doğrulamada Rescan Thumbnails / Verify Index / toplu thumbnail doğrulaması
çalıştırılmasına gerek yoktur. Yeni log, kimlik, tam URL, HTML veya cookie içermez.

## Duraklama noktası ve sonraki paket

İlk iki paket tamamlandı. 7–13 optimizasyonları bu görevde uygulanmadı:

- 500 ms poll, 45 s deadline ve 55 s watchdog arasında gerçek çakışma kanıtlanmadı.
  Yeni cihaz logları desteklerse main-thread lag ölçümü ve adaptif poll değerlendirilir.
- 2/4/10 gerçek WebView için aynı cihazda timeout, başarı, bellek ve süre
  karşılaştırması yapılmadan worker varsayılanı değiştirilmez. Mevcut tercih korunur.
- Schedule UI açıklaması, ayrı processed/download hız metrikleri, PublicProfileProvider
  seçim/UA ve mevcut FETCHER-INTEGRATION/FETCHER-040 dokümanlarının genel uyumu
  üçüncü pakette ele alınacaktır.
- Ambiguous image seçimi ve canonical fallback'in daha ileri değiştirilmesi,
  yeni diagnostics veya doğrudan temsilî fixture kanıtına bağlıdır.
- Önceden SKIPPED_UNAVAILABLE olan kayıtlar için toplu işlem yoktur. Yeni
  sınıflandırma cihazda doğrulandıktan sonra sınırlı ve kullanıcı onaylı retry
  yaklaşımı ayrıca değerlendirilmelidir.
