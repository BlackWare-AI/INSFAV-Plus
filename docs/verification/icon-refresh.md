# Supplied icon refresh, same 1.1.11 / build 14

The owner-supplied JPEG replaces all three active copies, without image generation,
re-encoding or layout changes. Its SHA-256 is recorded in branding.json. The APK
was signed with the existing approved certificate and published under a separate
icon tag; previous tags and assets remain unchanged.

The downloaded release was verified again using apksigner/aapt2. VersionName,
versionCode and signer equal the previous 1.1.11/build 14 release. Comparing ZIP
payloads after excluding signing files shows two changed artwork entries:
assets/logo.jpg and res/ST.jpg (the optimized launcher resource). Both equal the
supplied original bytes. The build source-revision record also advances to the
exact reviewed commit, with no other metadata edits. Code, manifest, resource table and all other payload
entries are identical to the previous APK.

The one-off same-build publication was guarded at app source commit
bfa897078306108e8584f955f3b7cfcd70951b31: 26 publication tests passed, including
rejection of code/manifest/version/signer/image changes. After the initial
publication stopped on the expected source-revision stamp change, 27 guard tests
validated exact provenance handling and the already-signed artifact was reused; 217 dialog checks and
the core JS tests passed locally. The full checks, emulator, session-durability
and three-app isolation gates passed before signing/publication. The ordinary
publication script/workflow and their tests are restored after this authorized
one-off operation, so the standard increasing-build policy remains unchanged.

The public APK download, original image, baseline payload comparison, signature
and completed workflow records are in release-1.1.11-icon.json. Physical-device
launcher appearance/cache behavior remains unverified.
