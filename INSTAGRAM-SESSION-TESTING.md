# Instagram session validation

The session workflow runs native unit tests, lint, builds and an offline Chromium durability fixture. The fixture seeds a synthetic persistent cookie and localStorage, then checks them after a new process, force-stop, intentional process crash, background kill request, APK update install and emulator reboot. It also preserves separate thumbnail/queue sentinels. A scoped logout check also verifies that the manual browser profile, another origin, and the file/queue sentinels survive Instagram logout. These sentinels are not a live Instagram download or proof that Android actually reclaimed a background process.

Existing engine/codec/storage tests cover queue recovery, security stops and preservation of successful thumbnails. Session tests cover default selection, method independence, durable security holds, HTTPS login navigation and verification without automatic queue resumption.

Not tested without a user-controlled account/device: first real login, real 2FA/checkpoints, real session invalidation, force-stop/crash/reboot with a live Instagram session, physical-device memory reclamation, and simultaneous live sessions in the three apps. Package IDs remain distinct; no shared cookie transport is added.

Actions run links and final results are recorded in the pull request/delivery report. A test definition alone is not a passing result.

## Browser cookie snapshots and YTDLnis HD

Both browser selections use the persistent WebView for HD; no native HTTP profile API or mandatory session test is a prerequisite. BrowserCookieSnapshotTest covers API-first values, unavailable/empty/incompatible metadata, stale/deleted cookies, Netscape round-trip, matching Chromium expiry, encrypted read-back, unchanged-write deduplication, per-method removal and logout/removal epochs.

The native cookie/HD fixture rotates a synthetic HttpOnly cookie between HD opens and waits for the selected encrypted snapshot to reflect it. It checks removal preserves the other snapshot and live login, automatic updates cannot resurrect the removed record, and explicit Save re-enables updates. Existing repeated-image metadata-only, unchanged thumbnail SHA, viewer-close conversion handoff and bulk-off assertions remain. This is controlled emulator evidence, not a live Instagram account test.
