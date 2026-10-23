# Verification evidence

`phase1.json` through `phase10.json` retain the actual mandatory command exits,
Java test counts and lint categories at each completed checkpoint. Previous green
checkpoints form the before-state for the following phase. JavaScript core tests
grew from 11 to 16; the final separate Chromium command runs nine functional
scripts. Avatar layout alone covers twelve dataset/viewport combinations,
including 10, 1,000, 10,000 and 15,000 accounts.

Owner safety-review native checkpoint: `357b6f7`; frontend/branding follow in the
same delivery. All 272 Java tests pass with zero
failures, errors or skips. All 16 core tests and nine browser scripts pass.
Debug lint has zero errors and zero warnings. Debug APK and AndroidTest APK build
successfully. `coverage.json` records actual JaCoCo counters: 4,195/6,271 lines
(66.90%) and 2,134/3,805 branches (56.08%). This is application unit-test coverage,
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

Chromium scripts have a 120-second process deadline and identify each script
before execution. A timeout/signal/nonzero exit fails the suite explicitly; it is
never counted as a passing or skipped script. The CI verify job has a 20-minute
limit. Both installed Chromium and Playwright Chromium 156 passed locally.

The cancelled run 39875424725 launched the runner-provided system Google Chrome
through CHROME_BIN; it exited with SIGTRAP before loading the app. An open test
server then kept Node alive. CI now explicitly unsets that override to use its
pinned Playwright browser; the functional UI script no longer forces the legacy
single-process/no-zygote flags. The launch-failure path now closes its HTTP server (verified with a missing
executable: nonzero exit in 0.31 seconds). The process deadline bounds failures
in the remaining scripts. The optional render harness also drops those flags;
its retained older numeric trace is labelled historical.

`owner-safety-review.json` records the final owner-review gates (272 Java tests).
The earlier six journal recovery tests passed again before retaining batched
snapshots; a seventh verifies all 63 pre-threshold acknowledged records after
restart. `performance/batch-size-comparison.json` measures the same scanner at
256/512; no overall improvement supports raising the default above 256.
`performance/deadline-comparison.json` documents the virtual-time 15/30/45 matrix
and conservative 45-second choice, not a live failure rate. The avatar matrices
now verify visible clipping, complete accessible labels and scrollable full text
via touch hold and keyboard. `branding.json` identifies the owner's unmodified
JPEG and generated copies. The subsequent selector UI correction removes the
image from About while retaining the supplied launcher artwork; the browser
assertion now verifies that the information modal has no image.
Full test and coverage reports were retained before a separate clean APK build
to avoid comparing incremental dex packaging overhead with a clean baseline.

The selector UI correction is covered by `tests/choice-dialogs.cjs`, included in
`npm run test:chromium`. It exercises all seven outcome options, the existing
language bridge, touch events, keyboard and Back dismissal, focus restoration,
large text and launcher artwork references. Existing language persistence,
collection membership and counter regression suites remain in the same command.

Local selector verification passed: 16 core JavaScript tests, all 10 Chromium
groups (including 217 choice-dialog assertions), 272 JVM Android tests, zero
lint issues, debug assembly and APK signature/package verification. The dialog
matrix includes English/Turkish, 320/360/480px widths, 200% root text size,
synthetic touch events and both reduced and normal motion. Regeneration produced
identical bytes for all 24 checked packaged resources/maps. Java JaCoCo reports
4,195/6,271 covered lines and 2,134/3,805 covered branches; it does not measure
web coverage. The initial pre-change Android attempt failed 92/272 tests when
the existing `/tmp` test workspace ran out of space; removing only disposable
synthetic test directories let the same source pass all 272 tests. The final
post-change run also passed. No physical-device or live Instagram test was run
for this UI correction. The resulting APK is debug signed, not an official release.
