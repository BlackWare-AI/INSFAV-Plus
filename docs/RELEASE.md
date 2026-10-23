# Plus delivery

CI is restricted to `BlackWare-AI/INSFAV-Plus`. Legacy executable workflows and
one-time tooling are archived outside active paths. The original signing block
and its environment variable names remain unchanged in Gradle. The Plus-only
manual workflow maps separately provided `PLUS_*` secrets to that existing block.
No secret is echoed, committed, attached to diagnostics or generated automatically.

Owner delivery preference: publish signed release APKs for requested deliveries.
Debug APKs remain internal CI/test artifacts unless the owner explicitly requests
one. The manual publish workflow defaults to release.

Every Plus release must use the same package and signing certificate as earlier
Plus releases, with a strictly increasing versionCode. Publication rejects a
changed signing certificate so future APKs remain compatible with in-place
updates. An installed APK's signing certificate must also match; the release
build type alone cannot make differently signed APKs compatible.

For a signed release, an owner must
provide an existing secure Plus keystore or explicitly authorize key creation
outside this workflow, configure the `plus-release` environment and dispatch
`publish.yml` with the reviewed version and code. Required tests and emulator
checks must pass first. All published APK versionCodes must increase. APK
metadata from previous releases is checked before a new release is created.
Official artifacts are verified as non-debuggable and signed, with the correct
package, version, build and public certificate/checksum metadata.

No release is created for a debug dispatch. No workflow targets the old project.

## Owner-authorized initial key and local signing

On 2026-10-23 the owner explicitly authorized creation of the initial permanent
Plus release key. Its public SHA-256 certificate fingerprint is:

`9090544d4da9428880aa032a35e44de61a83dd46eca88e4cc04cf16a0da22f0f`

This connection cannot administer GitHub environment/repository secrets (HTTP
403). The initial key and its passwords are kept in owner-only, Git-ignored
`.private/signing/` storage, with an owner-only recovery ZIP. They must never be
committed, logged or uploaded as a public release or Actions artifact. The
workspace copy is not a substitute for an owner-held offline/password-manager
backup. Keep the private recovery ZIP outside transient execution workspaces.

For an authorized local delivery when secret administration is unavailable, run
`python3 tools/sign-local-plus.py` with JDK 17 and Android SDK configured. This
helper only reuses the restored key; it cannot create or replace one. Pass the
existing current-source CI and emulator gates, verify with `tools/verify-apk.py`,
and publish through `tools/publish-plus.py` with the reviewed source commit.
Its package, increasing versionCode and stable certificate guards still apply.
The ordinary `publish.yml` workflow remains the default once the owner configures
its four `PLUS_*` environment secrets from the private backup.

The first signed release cannot update an installed APK signed with a different
key, including a prior debug build. Export settings/collections before removing
such an installation. Subsequent official releases must keep the fingerprint
above and increase versionCode.

When the connection also cannot authenticate to `uploads.github.com`, use the
optional `artifact_commit`, `source_sha` and `validation_run` inputs to the
existing publish workflow. An isolated, temporary staging commit contains only
the already-signed APK, public APK metadata and source SHA. The Actions job
requires a successful source-matching CI workflow with both verification and
emulator jobs, confirms unchanged app sources, verifies the signature/checksum
and pinned Plus certificate, and publishes with its own release token. No
keystore or password is transferred to that staging commit or job. Delete the
temporary staging branch after verifying the public download.
