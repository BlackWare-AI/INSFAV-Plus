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
