#!/usr/bin/env bash
set -euo pipefail
mkdir -p session-isolation-evidence
roots=(isolation/thumb isolation/insfav .)
packages=(com.insfav.thumbfetcher com.instagram.insfav com.instagram.insfav.plus)
runners=(com.insfav.thumbfetcher.PoolInstrumentation com.instagram.insfav.fetcher.PoolInstrumentation com.instagram.insfav.fetcher.PoolInstrumentation)
for i in 0 1 2; do
  adb install -r "${roots[$i]}/app/build/outputs/apk/debug/app-debug.apk"
  adb install -r "${roots[$i]}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  adb shell am instrument -w -e scenario seed "${packages[$i]}.test/${runners[$i]}" | tee "session-isolation-evidence/seed-$i.txt"
  grep -q OFFLINE_SESSION_SEEDED "session-isolation-evidence/seed-$i.txt"
done
check_all() {
  for i in 0 1 2; do
    adb shell am instrument -w -e scenario check "${packages[$i]}.test/${runners[$i]}" | tee "session-isolation-evidence/$1-$i.txt"
    grep -q OFFLINE_SESSION_PRESERVED "session-isolation-evidence/$1-$i.txt"
  done
}
check_all after-all-stores-seeded
adb reboot
adb wait-for-device
for attempt in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
  sleep 1
done
adb shell input keyevent 82
check_all after-reboot
