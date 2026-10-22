"""Package anonymous test evidence and a Turkish development report after the real emulator matrix."""
from pathlib import Path
import json, os, shutil, hashlib, struct, xml.etree.ElementTree as ET
from collections import Counter
import subprocess
root=Path.cwd()
build=root/"builds"
proof=root/"emulator-pool"
destination=root/"final-delivery"
rows=json.loads((proof/"pool-matrix.json").read_text())
background=json.loads((proof/"background-fixture.json").read_text())
assert all(row["sdk"]==34 for row in rows)
assert background["completed"]==8 and not background["userPaused"]
assert "POOL_MATRIX_OK" in (proof/"instrumentation.txt").read_text()
assert "BACKGROUND_HOME_TASK_OK" in (proof/"instrumentation.txt").read_text()
tests={name:0 for name in ["tests","failures","errors","skipped"]}
xml_files=list((build/"app/build/test-results").rglob("TEST-*.xml"))
assert xml_files, "Missing native test evidence"
for path in xml_files:
    suite=ET.parse(path).getroot()
    for key in tests: tests[key]+=int(suite.get(key,0))
assert tests["tests"]>=97 and not any(tests[key] for key in ["failures","errors","skipped"])
browser=(build/"browser-results.txt").read_text()
import re
uiChecks=int(re.search(r"(\d+) UI checks passed",browser).group(1))
assert uiChecks>=78
lint=dict(Counter(item.get("severity") for item in ET.parse(build/"app/build/reports/lint-results-debug.xml").getroot()))
assert not lint.get("Error",0)
shutil.copytree(build/"delivery",destination,dirs_exist_ok=True)
shutil.copytree(proof,destination/"verification/android-14",dirs_exist_ok=True)
shutil.copytree(build/"measurements",destination/"verification/thumbnail-measurements",dirs_exist_ok=True)
shutil.copytree(build/"app/build/test-results",destination/"verification/native",dirs_exist_ok=True)
for path in ["browser-results.txt","probe-results.txt","counter-results.txt"]:
    shutil.copyfile(build/path,destination/"verification"/path)
shutil.copyfile(build/"app/build/reports/lint-results-debug.txt",destination/"verification/lint-results-debug.txt")
source=os.environ.get("SOURCE_COMMIT","")
five=next(row for row in rows if row["requested"]==5 and row["minimumDelaySeconds"]==1)
one=next(row for row in rows if row["requested"]==1)
realistic=next(row for row in rows if row["requested"]==5 and row["minimumDelaySeconds"]==25)
assert five["intervalOverlap"] and five["maxActiveWebViews"]>=2
assert realistic["intervalOverlap"] and realistic["maxActiveWebViews"]>=2
speed=five["completedPerMinute"]/one["completedPerMinute"]
debug=destination/"INSFAV-debug.apk"
release=build/"app/build/outputs/apk/release/app-release-unsigned.apk"
verification={
    "application":"INSFAV","version":"3.4.0","build":6,"sourceInsfavCommit":source,
    "sourceFetcherCommit":"b3f03f2a30c36f89add5e954b9b121a7b9d899ae",
    "preservedInsfavCommit":"ac3d1b0565acd4dfbf82763dbc4fec42c124fbc1",
    "nativeTests":tests,"hostUiChecks":uiChecks,"lint":lint,
    "emulatorSdk":34,"emulatorUsesRealChromium":True,"emulatorUsesOfflineResponses":True,
    "emulatorMatrix":rows,"backgroundFixture":background,
    "speedMultiplierFiveVsOne":speed,"physicalDeviceTested":False,"liveInstagramBenchmark":False,
    "debugApkBytes":debug.stat().st_size,"releaseUnsignedBytes":release.stat().st_size,
    "debugApkSha256":hashlib.sha256(debug.read_bytes()).hexdigest(),
    "hdProbeSha256":hashlib.sha256((root/"app/src/main/assets/profile-probe-hd.js").read_bytes()).hexdigest(),
    "sourcePackageCommit":subprocess.check_output(["git","rev-parse","HEAD"]).decode().strip(),
    "sourceTree":subprocess.check_output(["git","rev-parse","HEAD^{tree}"]).decode().strip()
}
(destination/"Verification.json").write_text(json.dumps(verification,indent=2)+"\n")
# The new task's report supersedes the historical integration narrative above.
benchmark={}
for variant in ["before","after"]:
    suite=ET.parse(build/f"measurements/native-{variant}.xml").getroot()
    output=suite.findtext("system-out","")
    match=re.search(r"THUMBNAIL_BENCHMARK (\{.*\})",output)
    assert match, f"Missing native benchmark {variant}"
    benchmark[variant]=json.loads(match.group(1))
performance=json.loads((build/"measurements/browser-before-after.json").read_text())
verification["thumbnailNativeBenchmark"]=benchmark
verification["thumbnailHostBrowserBenchmark"]=performance
verification["screenOffFixturePassed"]=background["screenWasOff"] and background["screenOffAfter"]>background["screenOffBefore"]
assert verification["screenOffFixturePassed"]
verification["notificationAndFilesAgree"]=background["notificationDownloaded"]==background["validFiles"]==8
(destination/"Verification.json").write_text(json.dumps(verification,indent=2)+"\n")
report=(root/"docs/THUMBNAIL-OPTIMIZATION-TR.md").read_text()
report+="\n\n## Ölçülen sonuçlar\n\nAndroid/Robolectric: **%d test**, hata/başarısız/atlanan **0**. Chromium UI: **%d kontrol**. Lint: **0 hata**, **%d uyarı**.\n"%(tests["tests"],uiChecks,lint.get("Warning",0))
report+="\n### Native kaynak yolu — aynı sentetik test, API 34 Robolectric\n\n| Ölçüm | Önce | Sonra |\n|---|---:|---:|\n"
for key in ["requests","sourceOpens","directoryQueriesDuringReads","elapsedMs","responseBytes","originalBytesUnchanged","derivedCacheBytes","indexBytes","privateFilesBytes","privateCacheBytes","sourceFolderBytes"]:
    report+=f"| {key} | {benchmark['before'][key]} | {benchmark['after'][key]} |\n"
report+="\nBu süreler Robolectric/JVM ölçümüdür; fiziksel SAF gecikmesi veya telefon performansı değildir. Önceki diskteki sıcak kopya okumalarının yerine kaynak okumaları gelir; browser reuse bundan ayrı ölçülür.\n\n### Host Chromium — aynı kaydırma izi ve sentetik 256×256 WebP\n\n| Tekrar | Hesap | Sürüm | İlk açılış ms | FPS | Ortalama/P95/P99 frame ms | >32ms oranı | Tepe satır | HTTP görsel istekleri |\n|---|---|---|---:|---:|---|---:|---:|---:|\n"
for row in performance["samples"]:
    report+=f"| {row.get('attempt',1)} | {row['size']} | {row['variant']} | {row['initialMs']} | {row['fps']:.2f} | {row['frameMeanMs']:.2f} / {row['frameP95Ms']:.2f} / {row['frameP99Ms']:.2f} | {row['jankOver32Ms']:.3f} | {row['maxNodes']} | {row['requests']} |\n"
report+="\nHost Chromium FPS, Android ekran FPS'si değildir. JS heap ölçümü ayrı JSON'da vardır; PSS değildir. Native cache hit yerine eşzamanlı okuma birleştirme, JS browser Image reuse hit/miss ayrı tutulur. Browser'ın dahili decode cache hit oranı ve izole decode süresi: **Not Measured**.\n"
report+=f"\n### Arka plan ve bildirim\n\nAndroid 14 gerçek Chromium/offline fixture: Home {background['homeBefore']} → {background['homeAfter']}; ekran kapalı {background['screenOffBefore']} → {background['screenOffAfter']}; görev kaldırma sonrası {background['completed']}/8. Doğrulanmış kaynak dosyalar {background['validFiles']}; Downloaded {background['notificationDownloaded']}; sonuç bildirimi progress {background['notificationProgress']}.\n"
report+=f"\n### Paket\n\nDebug APK: {debug.stat().st_size} bayt. Optimize imzasız release: {release.stat().st_size} bayt. Kod commit: `{source}`. Kaynak Fetcher: `b3f03f2a30c36f89add5e954b9b121a7b9d899ae`.\n"
(destination/"Development-Report-TR.md").write_text(report)

report+="\n\n## Değiştirilen dosyalar\n\n"
changed=subprocess.check_output(["git","diff","--name-only","54b43f8151ded94ef0b6655c8575d519effe7fb6","HEAD"]).decode().splitlines()
report+="\n".join("- `"+name+"`" for name in changed)+"\n"
report+="\n## Üç tekrarın medyanları\n\n| Hesap | Önce/sonra FPS | Önce/sonra ilk açılış ms | Önce/sonra tepe satır | Önce/sonra HTTP istekleri |\n|---|---|---|---|---|\n"
import statistics
for size in [5000,10000,20000]:
    groups={variant:[row for row in performance["samples"] if row["size"]==size and row["variant"]==variant] for variant in ["baseline","current"]}
    def pair(key):return " / ".join(f"{statistics.median(row[key] for row in groups[v]):.2f}" for v in ["baseline","current"])
    report+=f"| {size} | {pair('fps')} | {pair('initialMs')} | {pair('maxNodes')} | {pair('requests')} |\n"
report+="\nFPS'de genel bir artış kanıtlanmadı. İlk açılış bazı örneklerde daha yavaştı; DOM ve HTTP istekleri her satırda azalmış gibi sunulmaz. Kesin doğrulanan değişiklikler; kaynak baytlarının korunması, liste yeniden kodlamasının kaldırılması ve türetilmiş disk cache'in sıfırlanmasıdır.\n"
report+="\n## Gerçek Android WebView havuzu — offline fixture\n\n| Requested | Effective | Peak active WebViews | Peak accounts | Peak CDN | Accounts/min | App PSS KiB |\n|---|---|---|---|---|---|---|\n"
for row in rows:report+=f"| {row['requested']} | {row['effective']} | {row['maxActiveWebViews']} | {row['maxActiveAccounts']} | {row['maxCdnReaders']} | {row['completedPerMinute']:.2f} | {row['appPssKiB']} |\n"
report+="\nSon 5 işçi satırı 25 saniyelik bekleme ayarında ilk dalgayı ölçer; uzun dönem hız değildir. PSS yalnız ana uygulama sürecinin işlem sonu ölçümüdür; ayrı Chromium renderer toplamını veya liste RAM karşılaştırmasını göstermez. Farklı profillerin gezinme ve CDN okuma aralıkları ham JSON'da çakışır.\n"
report+="\n## Paketleme ve imza sınırlaması\n\nUygulama testleri ve Android 14 emülatör senaryoları 39526391363 numaralı çalışmada geçti. O çalışmanın son paketleme adımı sığ Git geçmişinde eski karşılaştırma commit'ini bulamadı. Tam geçmişle yalnız paketleme yeniden çalıştırıldı; derlenmiş uygulama kodunun test edilen commit ile aynı olduğu git diff guard ile kontrol edildi.\n\nCI debug sertifikası önce teslim edilen 3.3.0 CI debug sertifikasından farklıdır; dolayısıyla o APK üzerine doğrudan güncelleme yapılamaz. Mevcut uygulamayı silme veya veri temizleme önerilmez. Veri ve sayaçları koruyan yerinde güncelleme aynı eski imzalama anahtarını gerektirir. Eski CI anahtarı elde değildir. Yeni APK temiz kurulum için çalışır; imza karşılaştırması kamuya açık sertifika SHA-256 değerleriyle doğrulanmıştır.\n"
def signing_certificate_sha256(apk):
    eocd=apk.rfind(b"PK\x05\x06");cd=struct.unpack_from("<I",apk,eocd+16)[0]
    assert apk[cd-16:cd]==b"APK Sig Block 42", "Missing APK signing block"
    size=struct.unpack_from("<Q",apk,cd-24)[0];pos=cd-size
    def read(data,offset=0):
        length=struct.unpack_from("<I",data,offset)[0]
        return data[offset+4:offset+4+length],offset+4+length
    while pos<cd-24:
        length=struct.unpack_from("<Q",apk,pos)[0];key=struct.unpack_from("<I",apk,pos+8)[0];value=apk[pos+12:pos+8+length];pos+=8+length
        if key==0x7109871a:
            signers,_=read(value);signer,_=read(signers);signed,_=read(signer);_,offset=read(signed);certificates,_=read(signed,offset);certificate,_=read(certificates)
            return hashlib.sha256(certificate).hexdigest()
    raise AssertionError("Missing V2 APK signer")
previousCertificate="b5c44d4e8a0480d4b939740170c820e799f9d1ea37b9d83d78bde79735fa56f8"
currentCertificate=signing_certificate_sha256(debug.read_bytes())
verification["signingCompatibility"]={"previousCiDebugVersion":"3.3.0","sameSigner":currentCertificate==previousCertificate,"previousCertificateSha256":previousCertificate,"newCertificateSha256":currentCertificate}
(destination/"Verification.json").write_text(json.dumps(verification,indent=2)+"\n")
(destination/"Development-Report-TR.md").write_text(report)
for path in destination.iterdir():
    if path.is_file():print("DELIVERY_FILE",path.name,path.stat().st_size,hashlib.sha256(path.read_bytes()).hexdigest())
print("DELIVERY_VERIFICATION",json.dumps(verification))
