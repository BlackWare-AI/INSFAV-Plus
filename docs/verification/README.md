# Verification evidence

`phase1.json` through `phase10.json` retain the actual mandatory command exits,
Java test counts and lint categories at each completed checkpoint. Previous green
checkpoints form the before-state for the following phase. JavaScript core tests
grew from 11 to 16; the final separate Chromium command runs nine functional
scripts. Avatar layout alone covers twelve dataset/viewport combinations,
including 10, 1,000, 10,000 and 15,000 accounts.

Final local source: `c6b0e63` (the subsequent delivery commit changes documentation,
benchmark orchestration and CI triggers). All 268 Java tests pass with zero
failures, errors or skips. All 16 core tests and nine browser scripts pass.
Debug lint has zero errors and zero warnings. Debug APK and AndroidTest APK build
successfully. `coverage.json` records actual JaCoCo counters: 4,170/6,255 lines
(66.67%) and 2,125/3,791 branches (56.05%). This is application unit-test coverage,
not Android device coverage. XML and HTML reports are attached by CI.

`debug-apk.json` describes the local test build, including its **public**
certificate digest and APK checksum. CI produces a separate debug certificate
and verifies/attaches its own metadata; use the metadata in the same downloaded
artifact when checking that APK. No release key or official release was created.

`failed-attempts.json` preserves earlier nonzero command exits. The delivery
report explains corrections, including compilation failures where prior test
counts would have been stale. Baseline full Android instrumentation crashed in
the local software emulator; it is not counted as passing or attributed to an
application defect without evidence. Plus's API 35 CI emulator suite passed at
`85cb932`; those numeric reports and their exact CI provenance are retained in
`emulator-api35/`. The final PR's Checks tab supplies the current emulator and
coexistence results. Device fixture success is not physical-phone or live
Instagram verification, nor an induced six-hour foreground-service timeout.

`performance/` contains paired, offline host numbers. Browser thumbnail trace
arrays are omitted from the compact report; all summary measurements remain.
`PERFORMANCE.md` identifies sample sizes, memory-backed filesystem limits,
regressions and unmeasured Android RAM peaks. The emulator directory contains a
single Plus scan run rather than an original-versus-Plus speed comparison.

Reproduce the complete checks with:

```sh
python3 tools/verify.py --phase verification --browser
./gradlew :app:jacocoDebugReport :app:assembleDebugAndroidTest
python3 tools/verify-apk.py app/build/outputs/apk/debug/app-debug.apk
```

CI additionally runs `tools/coexist.py` and `tools/emulator-pool.sh` on API 35.
Only run the former on an emulator: it checks both APK identities before
installation, never replaces/uninstalls the original package, checks distinct
UIDs and starts each app. No test contains real user collections or credentials.
