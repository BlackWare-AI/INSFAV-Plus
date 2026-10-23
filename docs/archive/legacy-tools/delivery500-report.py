"""Package current test evidence without reusing historical version or signing claims."""
from pathlib import Path
import hashlib, json, os, re, shutil, xml.etree.ElementTree as ET
root = Path.cwd()
build, proof, target = root / "builds", root / "emulator-pool", root / "final-delivery"
versions = dict(line.split("=", 1) for line in (root / "version.properties").read_text().splitlines() if "=" in line)
suites = [ET.parse(p).getroot() for p in (build / "app/build/test-results").rglob("TEST-*.xml")]
tests = {key: sum(int(s.get(key, 0)) for s in suites) for key in ("tests", "failures", "errors", "skipped")}
assert suites and not any(tests[k] for k in ("failures", "errors", "skipped"))
output = "\n".join(s.findtext("system-out", "") for s in suites)
match = re.search(r"FETCHER_13500 first_ms=(\d+) warm_ms=(\d+) first_listings=(\d+) first_opens=(\d+) first_decodes=(\d+) warm_listings=(\d+) warm_opens=(\d+) warm_decodes=(\d+)", output)
assert match, "Missing 13,500-file measurement"
benchmark = dict(zip(("firstMs", "warmMs", "firstListings", "firstOpens", "firstDecodes", "warmListings", "warmOpens", "warmDecodes"), map(int, match.groups())))
assert benchmark["warmListings"] == benchmark["warmOpens"] == benchmark["warmDecodes"] == 0
rows = json.loads((proof / "pool-matrix.json").read_text())
assert all(r["sdk"] == 34 and r["errors"] == 0 for r in rows)
assert any(r["requested"] == 5 and r["intervalOverlap"] and r["maxActiveWebViews"] >= 2 for r in rows)
background = json.loads((proof / "background-fixture.json").read_text())
assert background["validFiles"] == background["notificationDownloaded"] == background["completed"] == 8
assert background["completionNotificationRemoved"]
assert background["screenWasOff"] and background["screenOffAfter"] > background["screenOffBefore"]
rescan = json.loads((proof / "rescan-15000.json").read_text())
assert rescan["coldOpens"] == 15000 and rescan["warmOpens"] == rescan["sourceWrites"] == 0
assert rescan["sourceBytesAndDatesUnchanged"] and not rescan["restartRelistedFolder"]
rescan_comparison = json.loads((build / "measurements/rescan-before-after.json").read_text())
browser = (build / "browser-results.txt").read_text()
ui = int(re.search(r"(\d+) UI checks passed", browser).group(1))
lint = ET.parse(build / "app/build/reports/lint-results-debug.xml").getroot()
assert not any(item.get("severity") in ("Error", "Fatal") for item in lint)
shutil.copytree(build / "delivery", target, dirs_exist_ok=True)
shutil.copytree(build / "app/build/test-results", target / "verification/native", dirs_exist_ok=True)
shutil.copytree(proof, target / "verification/android-14", dirs_exist_ok=True)
shutil.copytree(build / "measurements", target / "verification/measurements", dirs_exist_ok=True)
for name in ("browser-results.txt", "probe-results.txt", "counter-results.txt"):
    shutil.copyfile(build / name, target / "verification" / name)
shutil.copyfile(build / "app/build/reports/lint-results-debug.txt", target / "verification/lint-results-debug.txt")
record = dict(version=versions["VERSION_NAME"], build=int(versions["VERSION_CODE"]), sourceCommit=os.environ["SOURCE_COMMIT"],
              nativeTests=tests, browserChecks=ui, fetcher13500=benchmark, android14OfflineFixture=rows,
              backgroundFixture=background, thumbnailRescan=rescan, rescanComparison=rescan_comparison,
              physicalDeviceTested=False, liveInstagramTested=False)
for name, p in (("debug", target / "INSFAV-debug.apk"), ("unsignedRelease", build / "app/build/outputs/apk/release/app-release-unsigned.apk")):
    record[name] = dict(bytes=p.stat().st_size, sha256=hashlib.sha256(p.read_bytes()).hexdigest())
(target / "Verification.json").write_text(json.dumps(record, indent=2) + "\n")
report = f"""# INSFAV {versions["VERSION_NAME"]} — Geliştirme ve test raporu

Fetcher için ayrı SQLite metadata indeksi ve kalıcı işlem durumları korunur. LocalImages taraması ve ImageCodec doğrulaması salt okunur, kontrol noktalı Rescan için optimize edildi. Store JSON biçimi, thumbnail görüntüleme, sanal liste çekirdeği ve CSS korunur. HD edinme ve RAM görüntüleme zinciri korunmuştur.

## Normal Rescan — 15.000 sentetik gerçek WebP dosyası

JVM/Robolectric deneyi aynı harness'i önceki ba7812f ve yeni kodla çalıştırır;
her SAF açılışında 1 ms yapay gecikme vardır. Fiziksel telefon ölçümü değildir.

| Ölçüm | Önce | Sonra |
|---|---:|---:|
| İlk tarama (ms) | {rescan_comparison["before"]["coldMs"]} | {rescan_comparison["after"]["coldMs"]} |
| Sıcak tarama (ms) | {rescan_comparison["before"]["warmMs"]} | {rescan_comparison["after"]["warmMs"]} |
| İlk açma sayısı | 30000 | 15000 |
| Sıcak açma sayısı | 0 | 0 |

Android 14 emülatöründe gerçek SAF/Binder ile, yapay gecikmesiz ayrı deney:
ilk {rescan["coldMs"]} ms, sıcak {rescan["warmMs"]} ms. İki worker kullanıldı.
Sıcak tarama dosya açmadı; yeniden açılış klasörü listelemedi. Kaynak içerikleri
ve lastModified değerleri değişmedi; kaynak yazma/silme girişimi yok.
PSS anlık önce/sonra: {rescan["pssBeforeKiB"]} / {rescan["pssAfterKiB"]} KiB;
bu değerler peak bellek ölçümü değildir. Ayrıntılar docs/RESCAN-PERFORMANCE.md
ve verification/measurements/ içindedir.

## Ölçüm — 13.500 sentetik gerçek WebP dosyası

Bu sonuçlar Robolectric/JVM + yerel test DocumentsProvider ölçümüdür; fiziksel telefon SAF performansı değildir.

| Ölçüm | İlk doğrulama | Kayıtlı indeksten başlatma |
|---|---:|---:|
| Süre (ms) | {benchmark["firstMs"]} | {benchmark["warmMs"]} |
| Tam klasör listeleme | {benchmark["firstListings"]} | {benchmark["warmListings"]} |
| Görsel stream açma | {benchmark["firstOpens"]} | {benchmark["warmOpens"]} |
| Bounds/decode çağrıları | {benchmark["firstDecodes"]} | {benchmark["warmDecodes"]} |

İlk doğrulama çözünürlük ve okunabilirlik için iki okuma yapar. Sonraki başlatma, Pause/Resume ve veritabanını yeniden açma testleri kayıtları kullanır. Normal INSFAV indeksi zaten geçerliyse ilk Fetcher aktarımı da görsel açmaz. Klasörün varlığını doğrulayan tek metadata sorgusu görsel listelemesi değildir.

## Doğrulamalar

- Android/Robolectric: {tests["tests"]} test; hata/başarısız/atlanan: 0.
- Chromium arayüz: {ui} kontrol.
- Android lint: Error/Fatal 0.
- Android 14 emülatörü: gerçek Chromium WebView'ler, çevrimdışı profil/CDN yanıtları; farklı profillerin işlem aralıkları çakıştı.
- Arka plan fixture: Home, ekran kapalı ve görev kaldırıldıktan sonra 8 doğrulanmış dosya; Downloaded bildirimi 8.
- SQLite sürüm yükseltme, tamamlanmış/başarısız/atlanmış durumlar ve deneme sayıları korunuyor.
- 429 sonrası cooldown boyunca yeni istek yok; süre bitiminde aynı kuyruktan devam.
- Çözünürlük ayrıntıları kalıcı ve sayfalı; bozuk görseller ayrı kalıyor; beklenmeyen çözünürlük silinmiyor.
- Tamamlandı bildirimi 6 saniye zaman aşımıyla kaldırılır; fatal hata ve rate-limit bildirimi kalır. Platform timeout yapılandırması otomatik testlidir.

## Sınırlamalar

Telefonu yeniden başlatma, fiziksel cihaz Force Stop, OEM kısıtlamaları ve canlı Instagram oturumuyla indirme bu çalışmada test edilmedi. DB yeniden açma testi fiziksel reboot testi değildir. SAF sağlayıcısı kesin dosya bildirimi vermezse dış değişiklikler otomatik keşfedilemeyebilir: Verify / Repair Fetcher Index veya normal Rescan gerekir. Bozuk SQLite dosyası sessizce silinmez; işlem durur ve kayıtlar korunur. Android otomatik devamı erteleyebilir.

Bu paketteki debug APK CI debug anahtarıyla imzalıdır. Resmî release için ayrı sertifika doğrulaması ve yayın kapısı gerekir; bu rapor tek başına güncelleme uyumluluğu iddia etmez.

Kaynak: {os.environ["SOURCE_COMMIT"]}
"""
(target / "Development-Report-TR.md").write_text(report)
print(json.dumps(dict(tests=tests, ui=ui, fetcher13500=benchmark)))
