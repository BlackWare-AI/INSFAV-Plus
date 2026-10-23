#!/usr/bin/env bash
set -euo pipefail
mkdir -p emulator-pool
apk=$(find app/build -name app-debug.apk -print -quit)
test_apk=$(find app/build -name '*debug-androidTest.apk' -print -quit)
adb install -r "$apk"
adb install -r "$test_apk"
adb shell pm grant com.instagram.insfav.plus android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell am instrument -w com.instagram.insfav.plus.test/com.instagram.insfav.fetcher.PoolInstrumentation | tee emulator-pool/instrumentation.txt
grep -q POOL_MATRIX_OK emulator-pool/instrumentation.txt
grep -q RESCAN_DEVICE_OK emulator-pool/instrumentation.txt
grep -q POLISH_UI_OK emulator-pool/instrumentation.txt
adb exec-out run-as com.instagram.insfav.plus cat files/polish-ui.json > emulator-pool/polish-ui.json
adb exec-out run-as com.instagram.insfav.plus cat files/rescan-15000.json > emulator-pool/rescan-15000.json
adb exec-out run-as com.instagram.insfav.plus cat files/pool-matrix.json > emulator-pool/pool-matrix.json
adb exec-out run-as com.instagram.insfav.plus cat files/background-fixture.json > emulator-pool/background-fixture.json
adb shell am start -W -n com.instagram.insfav.plus/com.instagram.insfav.MainActivity
sleep 4
adb shell dumpsys activity activities > emulator-pool/activity.txt
adb logcat -b crash -d > emulator-pool/crash.txt
adb shell pidof com.instagram.insfav.plus
grep -E '(mResumedActivity|topResumedActivity|ResumedActivity|Resumed:).*com.instagram.insfav.plus/com.instagram.insfav.MainActivity' emulator-pool/activity.txt
if grep -F 'Process: com.instagram.insfav.plus' emulator-pool/crash.txt; then exit 1; fi
