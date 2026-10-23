# Architecture

`MainActivity` hosts an isolated `https://insfav.local` WebView. Only local assets
and bounded image responses are served to the UI. The existing command switch
remains explicit; Android actions execute on dedicated I/O and scan executors.
`web/` is the UI source; `tools/minify.cjs` reproduces assets and source maps.
`web/i18n/catalog.tsv` generates both Web and Android language resources.

`Store` retains original collection JSON atomically, including unknown fields
and repeated memberships. Its public thumbnail JSON schema is unchanged. Image
metadata updates first enter an idempotent journal in the existing
`fetch_meta` SQLite table. Atomic snapshots at 64 changes/60 seconds and queue
completion acknowledge only committed revisions. Startup replays unacknowledged
deltas. Checkpoint SQLite version 1 and Fetcher version 5 remain unchanged.

`ThumbnailScanner` performs bulk SAF metadata queries, two validation workers
and 512-record transactions. Cancellation retains completed checkpoints. Warm
scans reuse unchanged metadata; source files are never deleted or rewritten.
`SourceBytes` bounds encoded data to 64 MiB. Concurrent reads share chunks and
release them when response streams close. One large read lease bounds concurrent
large payloads. No bitmap LRU or derived thumbnail disk cache is introduced.

`FetchBroker` owns a bounded hidden-WebView pool with completion/condition
wakeups, per-worker pacing, priority viewer work and shared access restrictions.
`QueueEngine` isolates profile failures, pauses for storage/database failures
and treats OOM as a global resource failure. A 15-second profile deadline remains
retryable. Candidate evidence vetoes a permanent unavailable-page decision.
WorkManager and the foreground service follow Android background limits;
`onTimeout` persists/defer claims and ends the foreground notification.

Android UID, internal databases, preferences, Keystore and WebView storage are
private to `com.instagram.insfav.plus`. Java namespace remains
`com.instagram.insfav`; providers use applicationId authorities. Backup is
disabled for private data. Only a user-selected SAF folder may be shared with
original INSFAV. Existing thumbnail contents and timestamps are preserved.

Diagnostics use fixed error codes and aggregate counters. Cookies, passwords,
account lists and private URIs are excluded. Cookie encryption remains the
original authenticated format, with a destination-UID Keystore key. Portable
settings archives authenticate the exact source package through AES-GCM AAD.

Source observation is registered independently of queue synchronization on cold
launch and resume. A lightweight source revision refreshes visible photos after
external changes even when the JSON index revision is unchanged. URI permission
loss and return change the snapshot scope. Restoring a folder changes its saved
selection, not its grants: inaccessible restored folders remain unavailable and
require the picker again. A directory change under the same tree invalidates the
old index as well.

Settings import durably disarms retrieval before any credential replacement.
Rollback restores the cookie, folder/language metadata and preferences; an
unconfirmed rollback keeps retrieval stopped and emits a fixed diagnostic code.
The UI does not assert successful preservation after an unconfirmed storage error.

The merged debug manifest uses `com.instagram.insfav.plus.androidx-startup` for
AndroidX initialization. WorkManager databases, unique-work names and notification
channels are scoped to the Plus UID even where their internal names match the
source. The instrumentation package is `com.instagram.insfav.plus.test`. SAF URI
grants and Android Keystore aliases are UID-scoped; matching alias strings do not
share keys. Backup/cloud extraction remain disabled for private state. No
sharedUserId or cross-app provider/URI access was added.
