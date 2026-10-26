# Instagram session validation

The session workflow runs native unit tests, lint, builds and an offline Chromium durability fixture. The fixture seeds a synthetic persistent cookie and localStorage, then checks them after a new process, force-stop, intentional process crash, background kill request, APK update install and emulator reboot. It also preserves separate thumbnail/queue sentinels. These sentinels are not a live Instagram download or proof that Android actually reclaimed a background process.

Existing engine/codec/storage tests cover queue recovery, security stops and preservation of successful thumbnails. Session tests cover default selection, method independence, durable security holds, HTTPS login navigation and verification without automatic queue resumption.

Not tested without a user-controlled account/device: first real login, real 2FA/checkpoints, real session invalidation, force-stop/crash/reboot with a live Instagram session, physical-device memory reclamation, and simultaneous live sessions in the three apps. Package IDs remain distinct; no shared cookie transport is added.

Actions run links and final results are recorded in the pull request/delivery report. A test definition alone is not a passing result.
