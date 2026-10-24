# Contributing to INSFAV Plus

Third-party contributions are welcome. Please open an issue for a larger change, then submit a pull request to `main`.

## Before you submit

- Keep INSFAV Plus an independent application and repository. Do not merge Plus code into `BlackWare-AI/INSFAV`, and do not change the application ID `com.instagram.insfav.plus`.
- Preserve existing collection formats, list memberships, 128×128 and 256×256 WebP thumbnail compatibility, export/import behavior and supported workflows.
- Keep scans and diagnostics within the documented read/write boundaries. Do not delete or modify user source media unless an existing, explicit feature requires it.
- Do not include cookies, personal JSON backups, account data, signing keys, keystores, passwords or other secrets in commits, logs, screenshots or test fixtures.
- Keep official releases signed with the existing Plus release key. Forks may build and distribute their own builds, but must not claim to be official Plus releases or update-compatible with BlackWare's signed APK.
- Do not overwrite or delete published release tags or assets. Never add signing secrets to a public fork.

## Pull requests

Describe the user-visible change, why it is needed, and how you verified it. Include relevant screenshots for UI changes and list any checks you could not run.

Use JDK 17, Android SDK 35/build-tools 35.0.0 and Node 22. Run the checks relevant to your change:

```sh
npm ci
npm run web
npm test
npm run test:chromium
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Edit the UI in `web/`; `npm run web` generates packaged assets and Android translations. Check generated asset reproducibility with `npm run web:check`.

A pull request is a proposal. Maintainers review and decide whether to merge it. Do not expect fork changes to be included in an official signed release automatically.
