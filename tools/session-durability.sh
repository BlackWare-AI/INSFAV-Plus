#!/usr/bin/env bash
set -euo pipefail
mkdir -p session-evidence
package=com.instagram.insfav.plus
runner=com.instagram.insfav.plus.test/com.instagram.insfav.fetcher.PoolInstrumentation
apk=$(find app/build builds -name app-debug.apk -print -quit 2>/dev/null || true)
test_apk=$(find app/build builds -name '*debug-androidTest.apk' -print -quit 2>/dev/null || true)
adb install -r "$apk"
adb install -r "$test_apk"
check() {
  adb shell am instrument -w -e scenario check "$runner" | tee "session-evidence/$1.txt"
  grep -q OFFLINE_SESSION_PRESERVED "session-evidence/$1.txt"
}
adb shell am instrument -w -e scenario seed "$runner" | tee session-evidence/seed.txt
grep -q OFFLINE_SESSION_SEEDED session-evidence/seed.txt
check reopen
adb shell am force-stop "$package"
check force-stop
adb shell am instrument -w -e scenario crash "$runner" > session-evidence/intentional-crash.txt 2>&1 || true
grep -q CRASHING_OFFLINE_FIXTURE session-evidence/intentional-crash.txt
check crash
adb shell am start -W -n "$package/com.instagram.insfav.MainActivity" >/dev/null
adb shell input keyevent KEYCODE_HOME
adb shell am kill "$package"
check process-death
adb install -r "$apk"
check update-install
adb reboot
adb wait-for-device
for attempt in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
  sleep 1
done
adb shell input keyevent 82
check reboot
