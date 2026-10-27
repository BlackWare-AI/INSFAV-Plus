# INSFAV Plus

<img src="web/static/logo.jpg" alt="INSFAV Plus" width="128">

[Download the signed INSFAV Plus 1.1.11 release APK](https://github.com/BlackWare-AI/INSFAV-Plus/releases/latest).

INSFAV Plus is an independent Android collection manager based on INSFAV commit
`0a4ba915418e0652618879d4af2c0745ef9ca4f0`. The original INSFAV repository and
INSFAV 5.0.0 remain separate.

- **App:** INSFAV Plus 1.1.11, build 14; `com.instagram.insfav.plus`.
- One Java/Gradle Android application, one APK; Android 8.0+ (min 26), target 35.
- AMOLED black interface, 60×60 profile images in contiguous 65px virtual rows.
- Optional 2/3/4/5-column square matrices with usernames below images; view choice is saved.
- A translucent scroll ball reveals on tap and slides halfway offscreen after 3s idle; its rail stays hidden. Arrows slide/fade after 3s; dragging uses a 250ms hold.
- Existing collection formats, list memberships, 128/256 WebP files, HD viewer,
  Fetcher, scheduling, notifications, WorkManager, SAF and diagnostics remain supported.
- English and Turkish, system language by default with a saved override.
- Read-only missing/present/orphan candidates relative to loaded collections; incomplete imports can misclassify images. No deletion.
- Hold an account name or use Shift+F10 to read full text at large font sizes.
- Download outcome filters keep list order and memberships; Refresh updates the explicit filter snapshot.
- Optional measured diagnostics, bounded redacted operation history and recoverable startup errors.

The optional Fetcher accesses a third-party service automatically. Its use may
conflict with that service's terms or access restrictions. Android controls when
background work can run; force stop does not guarantee an automatic restart.

## Build and verify

Use JDK 17, Android SDK 35/build-tools 35.0.0 and Node 22.

```sh
npm ci
npm run web
npm test
npx playwright install --with-deps chromium
npm run test:chromium
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:jacocoDebugReport
```

`CHROME_BIN` optionally selects installed Chromium. Browser tests recover their
baseline locally from the preserved Git bundle. `npm run test:performance` runs
host-browser comparisons separately. `python3 tools/verify.py --phase NAME
--browser` retains actual exit codes and reports under ignored `artifacts/`.

Edit the UI in `web/` only; `npm run web` generates packaged assets, Android
translations and source maps. `npm run web:check` checks reproducibility after a
clean checkout. [MIGRATION.md](MIGRATION.md) explains data transfer;
[ARCHITECTURE.md](ARCHITECTURE.md) describes storage and lifecycle;
[PERFORMANCE.md](PERFORMANCE.md) distinguishes measurements from device limits.

Debug APKs are test artifacts, never official releases. The manual Plus publish
workflow verifies package, versionCode, signature, checksum and non-debuggable
state before a release. It requires separately supplied Plus signing secrets;
no key is generated. See [release guidance](docs/RELEASE.md).

MIT application license: [LICENSE](LICENSE). Inter fonts retain their
[SIL Open Font License](INTER-LICENSE.txt). Original source history and legacy
release checksums/certificates are preserved in [docs/archive/source](docs/archive/source).
The current source is also preserved on `preservation/current-main-2026-10-24`.

## Open source and development

This public repository can be forked to study, modify and extend INSFAV Plus.
Third-party contributions are welcome through pull requests. Read
[CONTRIBUTING.md](CONTRIBUTING.md) before submitting changes. Plus remains a
separate application with its own package name, release history and signing key.

The detailed Turkish delivery report is [here](docs/IMPLEMENTATION-REPORT-TR.md);
[verification evidence](docs/verification/README.md) records counts, coverage and limits.

For paired offline measurements (Python 3.12+), run `python3 tools/benchmark.py --kind all`.
It restores the exact baseline bundle into ignored artifacts, adds external test
harnesses without editing tracked source, and disables the coverage agent for
both measurement variants. Run the full verification and coverage commands again
after these filtered benchmarks. Android, browser heap and host filesystem
results have separate scopes in PERFORMANCE.md.

Safety policy evidence: `python3 tools/benchmark.py --kind policies` compares
15/30/45-second profile deadlines and identical 256/512 scanner batches. The
selected defaults are 45 seconds (retryable) and 256 records with two workers.
See the scope and measured results in PERFORMANCE.md.
