# Official release signing

The **Official INSFAV 4.0.0 release** Actions workflow builds the current `main` commit. It first runs focused incremental rescan cases, the full Android unit suite, release lint and an unsigned release build. A separate preflight checks signing configuration without printing values. Publication requires both jobs to succeed.

Configure these repository **Actions secrets** using the existing intended private key:

| Secret | Value |
| --- | --- |
| `INSFAV_KEYSTORE_BASE64` | Base64-encoded existing keystore; never a newly generated fallback |
| `INSFAV_STORE_PASSWORD` | Existing keystore password |
| `INSFAV_KEY_ALIAS` | Existing signing key alias |
| `INSFAV_KEY_PASSWORD` | Existing private-key password |

Configure the repository **Actions variable** `INSFAV_RELEASE_CERT_SHA256` with the trusted signing certificate's SHA-256 fingerprint (64 hexadecimal characters; colons are accepted). This is public certificate metadata, not a password. Establish it from a trusted existing key/APK, not from an untrusted generated candidate merely to make the check pass. Verify the previous APK's certificate separately before asserting update compatibility.

Use GitHub **Settings → Secrets and variables → Actions**. Never commit the keystore, base64 data, passwords, cookies or private JSON. Do not paste them into issue comments, workflow files, PRs, logs or release assets. This repository's connected GitHub tool does not administer Actions secrets; an authorized repository administrator must configure them securely.

After configuration, run the workflow on `main` using **Actions → Official INSFAV 4.0.0 release → Run workflow**. The initial main-branch workflow change also triggers it. Missing configuration fails safely: Android tests still run, but no signed APK, tag or release is created.

The signing job decodes only the supplied existing key into a temporary file with restricted permissions. Passwords are passed as environment variables. Signing build output is discarded rather than exported as potentially sensitive logs. The temporary key is removed on exit and in an always-run cleanup step. No signing material is included in artifacts or caches.

The workflow checks the supplied certificate against the explicit pin, builds the optimized release, verifies the APK signature, certificate, application ID, version `4.0.0`, build `7` and non-debuggable flag, then computes the APK SHA-256. Only the verified APK and public verification metadata reach the publishing job. No unsigned/debug fallback is accepted.

Publication first creates a draft `v4.0.0` release at the tested source commit, uploads the APK and public checksums/certificate record, downloads the draft APK and rechecks its SHA-256, then publishes. Existing releases/tags are never overwritten or deleted. If final upload verification fails, a draft can remain for manual inspection; earlier releases are preserved. Publication also stops if `main` has advanced since the workflow started.

The release notes explicitly say that comparison against the previously installed APK has **not** been performed. Matching a configured certificate pin proves artifact identity; it does not by itself prove in-place compatibility with an older debug build. Never uninstall or clear user data to bypass a signature mismatch.
