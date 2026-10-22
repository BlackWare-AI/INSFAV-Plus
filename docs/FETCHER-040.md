# Fetcher 0.4.0 in INSFAV 3.3.0

Source: BlackWare-AI/INSFAV-Thumb-Fetcher, development/0.4.0, b3f03f2a30c36f89add5e954b9b121a7b9d899ae. Independent archived releases are untouched. The working INSFAV 3.2.0 tree is preserved by commit ac3d1b0565acd4dfbf82763dbc4fec42c124fbc1 on preserve-working-3.2.0.

## Root causes and changes

The previous integrated broker admitted one operation, regardless of a helper setting. The helper pool additionally limited pages by Java memoryClass/32 and extended a global nextRequestAt after every result. Its scheduled job forced one worker. INSFAV now shares one reusable provider/cookie lease across bounded concurrent profile operations. Android main-looper calls create/navigate/read DOM independently per WebView; HTTPS CDN transfers are independent worker calls. A coordinator serializes only queue/SAF commits, not profile retrieval.

Each worker reserves its own persistent workerNextAt start-to-start timestamp. Completion never advances another worker's clock. A waiting slot cannot block a different ready slot. The previous nextRequestAt remains only as a legacy/configuration startup gate; Retry-After remains pool-wide. Selecting five can therefore run five short profiles even with a 25-second per-worker interval. Fast profiles may leave their own slots idle until their next permitted start. Increasing the worker ceiling increases the aggregate request rate and does not guarantee protection from server rate limits. The UI distinguishes requested/effective/active WebViews, active accounts (including CDN), peak active accounts, completion rate, ETA and app PSS. WebView count does not represent isolated renderer processes.

Memory admission uses currently available system memory minus an OS reserve, a provisional 64 MiB/page estimate, and Java heap headroom at 12 MiB/operation for bounded source bytes/conversion. These are conservative estimates, not measured device allocations. Java memoryClass/32 and the unconditional low-RAM-device cap are removed. Actual low-memory/trim callbacks and renderer failures halve admission; active work drains rather than being arbitrarily discarded. Diagnostics expose limits and memory; app PSS excludes separately allocated renderer memory. Inspect the emulator matrix and use device diagnostics to calibrate real devices.

## Shared HD priority

The existing INSFAV HD probe is byte-identical. Local-first display, real dimensions, gestures, no permanent full-resolution file and token cancellation stay intact. Interactive requests drain the current batch operations, then precede queued bulk work through the same lease and pacing/cooldown. Closing a viewer cancels only its request, not other accounts. Per-account timeout is bounded at 110 seconds including provider/CDN work; WebView navigation/DOM has its own shorter deadlines and cleanup. Cookies cannot change during a batch or viewer. A changed generation or a globally blocked provider is replaced after operations drain.

## Queue and output

SQLite schema/persistence and main collection JSON remain compatible. Missing-image results become SKIPPED_NO_IMAGE without claiming account deletion. Explicit unavailable/404 becomes SKIPPED_UNAVAILABLE. From integrated build 10, completed timeout/network/processing outcomes are held for explicit Retry; pending profiles continue. The private SQLite v5 outcome ledger retains failures across JSON removal/readdition without suppressing new accounts. Global 429/login/challenge/access barriers pause the pool without consuming per-account attempts. Retry Skipped / Failed Profiles explicitly requeues those account-local outcomes. Source 0.4.0's broad skip-every-network-error policy is not imported.

New output is square center-cropped 256x256 WebP quality 78. Existing 128x128 WebP remains valid and is not automatically deleted/refetched. Legacy refresh was removed after 4.0.0. Valid existing thumbnails are preserved; Rescan is now read-only and does not prune orphan WebP files. See SETTINGS-THUMBNAIL-SAFETY.md. File names and external folder selection do not change. Existing safe rename/readback/journal/index updates are retained rather than replaced with the helper's separate output-folder manager.

## Background

The service uses stopWithTask=false and START_STICKY only after it owns a foreground job. Removing the UI task schedules recovery but does not cancel the service. Activity stop pauses only when Background Processing is off; closing the viewer is independent. WorkManager recovery uses the same pool in bounded two-minute admission windows, then drains bounded requests and reschedules with the persisted hours/cooldown. Cached scans remain bounded to 128 entries per recovery job. WorkManager interruption preserves pending work and schedules recovery. Android FGS quota timeout defers to the next work window. Pause/Stop remain explicit persistent controls.

Force Stop, system quota and vendor restrictions cannot be bypassed. Real device background acceptance remains necessary. A foreground notification does not guarantee Chromium renderer survival; renderer errors are account-local and trigger capacity reduction.

## Verification

The instrumentation matrix runs real Android Chromium with anonymous local HTTPS-response fixtures on the production navigation/resource path and the production trusted CDN reader with an injected offline connection. It measures actual navigation/completion intervals, active pages/accounts, CDN readers, throughput and app PSS for requested 1/2/5/10/30. It asserts overlap when effective capacity is at least two. These are emulator/offline results, not live Instagram benchmarks. Pure unit provider overlap tests are reported separately and never substituted for real WebView evidence. See the delivered Turkish report and raw matrix for actual outcomes and device gaps.
