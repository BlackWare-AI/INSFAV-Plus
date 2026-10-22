> Historical INSFAV 3.2.0 integration. For the current pool, 256x256 output and background behavior, see [FETCHER-040.md](FETCHER-040.md).

# Integrated Thumbnail Fetcher

The independent helper repository is preserved. INSFAV reuses its `cookie-background` branch, commit `dcfd070`, version 0.3.0. `fetcher-provenance.json` records SHA-256 of every imported source before package adaptation. The default discovery path remains the bounded, unattached Android WebView and original identity-bound `profile-probe.js`, followed by the trusted HTTPS CDN downloader and validated 128x128 WebP codec. No private API endpoint is added.

## User flow

Application Information → Settings → Thumbnail Fetcher. Paste and save cookies, configure minimum/maximum delay and local working hours, save the schedule, and choose Start Missing. No Test Cookies button or three-profile preflight gate is used. Authentication, challenge and rate-limit detection remain active during actual work. Cookie presence does not establish authenticated access.

The existing INSFAV collection document supplies unique normalized account names; users never import JSON again for the fetcher. Queue state and cookie preferences are separate from the existing Usage/Load/Reset preferences. The original document and its unknown fields remain unchanged.

The selected SAF tree and indexed directory are reused. Earlier versions persisted read-only grants; Android cannot silently promote them to write grants. Allow Writing to Selected Folder reopens the system picker at that exact tree and accepts only the same URI. Cancelling or choosing a different folder preserves the current selection/index. No broad storage permission is used. A parent tree with a `.thumbnails` child reuses the indexed child ID.

## Queue, priority and scheduling

The original foreground service, notification controls and WorkManager scheduling are retained. A single process-wide priority broker owns all WebView and CDN operations. A viewer request precedes queued batch work after the active operation completes; it cannot overlap another provider. Both kinds obey the same persisted next-request time and rate-limit cooldown. Interactive foreground viewing is allowed outside batch working hours; batch work remains schedule-bound.

Recognized unavailable-page text with no current image candidate becomes `SKIPPED_UNAVAILABLE` under the user-selected fast-skip policy; HTTP 200 or 404 alone is not proof of deletion. Missing image URLs, generic error pages and transient networking become `RETRY_LATER`. Each account-local unsuccessful attempt is saved and held until the user chooses Retry Skipped / Failed Profiles. Start Missing and Resume only continue untried or interrupted work; they never automatically repeat a completed unsuccessful profile attempt. A private SQLite outcome ledger also preserves that decision if an account is removed from JSON and later reintroduced. New JSON accounts without an outcome always remain eligible. 429, invalid sessions, challenges and access restrictions pause the shared pipeline. Resume is an explicit user action and cannot bypass Retry-After. Pause/Stop preserve the SQLite queue. Stop disarms background scheduling. Background Processing off pauses batch work when the Activity stops. WorkManager persists recovery when enabled; Android quotas, Doze and vendor restrictions can defer or stop execution.

## Local images and safe replacement

Existing valid indexed sources of all supported formats are skipped. Existing valid output WebPs can repair a missing index without fetching again. Batch output catalogs are reused instead of scanning the folder for every account. The existing local catalog receives a verified file URI directly, without requiring a full rescan. Cache revisions invalidate displayed thumbnails.

A new thumbnail is written to a unique private-named `.part` document, closed, reread and validated before touching an old final file. Refresh renames the old file to a unique `.bak`, promotes and validates the new final file, then removes the backup. A private atomic journal enables interrupted replacement recovery. Unsupported provider rename or rollback can leave the old bytes in the backup; they are never deleted before a new final is verified. Unrelated photos and collection JSON are never removed. Real SAF providers still require device acceptance tests.

## Fullscreen view

The local thumbnail is shown immediately, followed by the existing local larger preview when available. A token-scoped interactive request uses an HD variant of the same DOM probe to prefer the largest actually decoded, exact-account image available in the page's DOM/structured profile data. It never invents or modifies CDN URLs to claim nonexistent resolution. The accepted CDN file is container/decoder-validated and the actual decoded dimensions are shown. Instagram may expose only a small photo.

The original fetched bytes are held only in memory and served through an intercepted same-origin no-store resource. They are never written to a permanent file. The validated 128x128 derivative may refresh the thumbnail only when the selected tree has write permission. Zoom/pan state is retained when the online image replaces the local image; a late local callback cannot replace it again. Closing cancels pending viewer work, invalidates the token, zeroes retained native source bytes, releases frontend image references and clears the WebView cache. This does not claim immediate, measurable process RSS reduction or forensic erasure of Chromium's internal allocations.

The imported provider limits a CDN response to 8 MiB and rejects images above 40 megapixels/16384 per dimension. Thumbnails decode with sampling; full-resolution viewing necessarily uses more memory. On failure the local image stays visible.

## Session privacy

The helper's AES-GCM Android Keystore vault is reused in app-private storage. Cookie text is removed from the editor immediately on Save and is never included in snapshots, logs or exports. Diagnostics use a fixed vocabulary and bounded rotation, without account identifiers or URLs. The UI WebView still intercepts all resources locally and has no navigation to Instagram. Only the isolated provider loads remote pages, with no Android JavaScript bridge. Third-party cookies, external navigation, permissions, popups, file/content access and invalid TLS are blocked by the original provider.

Chromium cookie/storage data is platform-managed: startup and per-operation purges are retained. An interrupted process can leave private Chromium data until the next purge; no stronger secure-erasure claim is made. App backup/device transfer is disabled.

## Verification scope

Host Chromium tests use a simulated native bridge. Native tests use Robolectric and a synthetic SAF provider/provider responses. Neither is a real authenticated Instagram or physical-device test. The user's six successful images were located in Library metadata, but file transfer was denied in this run; those actual files and new live authenticated fetches could not be validated here. Six synthetic positive image writes are tested separately and are not described as the user's six downloads. Never provide session cookies to public source or diagnostic files.

Android foreground service behavior reference: https://developer.android.com/develop/background-work/services/fgs/timeout

Build 10 adds a Current Process card between counters and actions. It shows active/queued profiles, handled accounts this run, the last result code, reviewed progress, and the configured worker-delay countdown. Deferred outcomes remain RETRY_LATER, not unavailable. Invalid sessions and real rate limits still pause; interrupted/session-verification work is not recorded as a completed profile failure. Worker count, pacing, and deadlines are unchanged.
