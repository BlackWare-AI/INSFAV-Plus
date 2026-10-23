# Thumbnail preservation and settings backups

Rescan Thumbnails is read-only. It lists the selected folder's direct children and updates private metadata without modifying, deleting or renaming any source file or changing its modification time. This supersedes the earlier orphan-WebP cleanup behavior: an account missing from Favorites no longer causes deletion during Rescan. No additional write grant is requested. Removed files are removed only from replaceable index metadata after a complete listing; an interrupted or loading provider cannot prune the completed index. SQLite checkpoints preserve completed validation batches; the existing atomic JSON snapshot stays compatible with the viewer and Fetcher. See [RESCAN-PERFORMANCE.md](RESCAN-PERFORMANCE.md).

Existing valid 128×128 and 256×256 WebP files are preserved during Missing, Retry, Resume and HD viewing. HD retrieval, RAM-only source display, zoom and dimensions remain unchanged; the optional local write now fills missing/invalid thumbnails only. Legacy refresh is removed.

## Portable settings backup

Use **Settings → Data & Diagnostics → Export All Settings / Import All Settings**. The independent Fetcher exposes the same actions in its More menu and accesses only its own settings.

The version-1 `INSFAV_SETTINGS` JSON envelope encrypts the entire payload using AES-256-GCM, PBKDF2-HMAC-SHA256 (210,000 iterations), a random 16-byte salt and 12-byte nonce. The user supplies a password of at least eight characters; no key/password is stored in the backup. App identity and version are authenticated. Cookies are re-encrypted into the destination device's Android Keystore after import. Expired cookies can be backed up, but are still subject to normal authentication checks when used.

Backed-up preferences: minimum/maximum delay, start/end local minutes, Concurrent WebViews, Background Processing, cookies and the selected folder reference. These are all current configurable persistent preferences. Retry count is a fixed engine policy, not a saved preference. Server cooldowns, per-worker reservations, queue progress, transient state and session evidence are not portable preferences and are never reset to evade restrictions. Favorites, counters, thumbnail index, image bytes, logs and account queues are excluded. Imports validate before applying and leave retrieval stopped; tap Resume explicitly after review. A folder reference never grants storage access: a new device must select its folder through SAF.

## Working hours

Input uses 24-hour `HH:mm` from 00:00 to 23:59. Windows are start-inclusive/end-exclusive; equal start/end is rejected. Overnight windows such as 22:00–06:00 are supported. Each bulk dispatch is checked in the current device time zone. Existing active requests may finish after the window ends; queued requests cannot start outside it. Interactive HD requests are user initiated and remain independent of the bulk schedule. Clock/timezone/reboot broadcasts recalculate persisted WorkManager scheduling. Foreground/background transitions do not remove the schedule checks.

Android can defer WorkManager or restrict foreground services. Force Stop cannot be bypassed. A schedule is permission to start in a window, not a guarantee of exact wake-up timing. UI distinguishes working, outside schedule, background waiting and user-disabled states. Physical-device background, reboot and OEM behavior requires device acceptance testing.
