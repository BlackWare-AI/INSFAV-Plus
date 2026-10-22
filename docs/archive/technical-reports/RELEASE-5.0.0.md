# INSFAV 5.0.0

- Durable Fetcher-only SQLite image metadata and processing phases preserve work across restarts.
- Reuse the normal INSFAV catalog metadata without changing its renderer or Rescan implementation. Warm Start Missing and Resume do not enumerate or decode completed thumbnail files.
- Explicit **Verify / Repair Fetcher Index** validates external changes and repairs replaceable Fetcher metadata; it never deletes source images or the normal INSFAV index. Detailed provider change notifications invalidate only matching document records. Providers without reliable notifications require explicit verification or normal Rescan to discover external changes.
- **Invalid Resolution Images** shows persisted dimensions and paginated filenames. Accepted dimensions use the current codec target plus legacy compatibility; readable unexpected resolutions are preserved, and unreadable data is not counted as a resolution mismatch.
- Durable PENDING, DOWNLOADING, COMPLETED, SKIPPED, FAILED and RATE_LIMITED phases coexist with the existing downloaded/existing/failure counters. Failed and skipped items do not loop in the same run. Interrupted claims alone are rechecked.
- Shared server cooldowns retain pending work without consuming retry attempts. Background-enabled queues can resume through WorkManager after cooldown and within local working hours; Android may defer execution.
- Notifications distinguish startup, indexing, download, pause, rate limits, completion with failures, and unrecoverable errors. Monochrome icons and the system progress bar are used. Completed notifications time out; errors remain visible.

Local photos, JSON, cookies, normal Rescan optimizations, counters, scheduling and RAM-only HD viewing are retained. The previously published APKs and release tags are not replaced. Version 5.0.0 uses build 8 and the existing release certificate.

SQLite metadata is replaceable; the account queue is not silently discarded. If SQLite itself cannot be opened, retrieval stops with preserved database files rather than automatically deleting progress. Android Force Stop prevents automatic work until the app is opened again. Physical-device/OEM acceptance results are reported separately from synthetic, JVM and emulator measurements.

## Build 9 — Fetcher fast skip

Version name remains 5.0.0; build is increased from 8 to 9. Explicit unavailable-page text without a current profile image candidate now produces an immediate skip. See [build 9 evidence and limitations](FETCHER-BUILD9.md). The earlier build-8 artifact and release tag are preserved. The new release must be signed with the same original certificate.
