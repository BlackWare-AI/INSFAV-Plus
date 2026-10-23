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
and 256-record transactions. Cancellation retains completed checkpoints. Warm
scans reuse unchanged metadata; source files are never deleted or rewritten.
`SourceBytes` bounds encoded data to 64 MiB. Concurrent reads share chunks and
release them when response streams close. One large read lease bounds concurrent
large payloads. No bitmap LRU or derived thumbnail disk cache is introduced.

`FetchBroker` owns a bounded hidden-WebView pool with completion/condition
wakeups, per-worker pacing, priority viewer work and shared access restrictions.
`QueueEngine` isolates profile failures, pauses for storage/database failures
and treats OOM as a global resource failure. A 45-second profile deadline remains
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

The 15/30/45-second watchdog comparison uses virtual-time normal/HD delayed
valid-profile fixtures. The conservative 45-second baseline avoids the false
timeouts introduced by shorter limits; timeout remains retryable. These fixtures
do not estimate live Instagram latency or failure rates. The identical Plus
scanner was compared at 256/512 batches over three counterbalanced pairs of
15,000 files. With no wall-time improvement at 512, production retains 256.

Long account labels keep fixed 65px rows. Holding the name, using its context
menu, or Shift+F10 opens a scrollable full-text dialog. Plain labels and complete
accessible names remain available; a short tap still opens the profile. Orphan
inventory is relative only to imported collections, warns about incomplete
imports, and does not classify files when no collection is loaded. No delete
operation is exposed. User-supplied logo bytes are generated from one web source
into Web and Android assets without re-encoding.

The main list keeps document scrolling and its existing virtual rows. A fixed
52px overlay sits 6px from the viewport edge without reserving list width.
Its arrows jump to the document's actual bounds; the scrollbar derives its
position from scrollHeight/clientHeight; its 40px ball and 52×56px touch target stay fixed. Only pointer dragging needs
a continuous 250ms hold, with an 8px cancellation threshold before
activation. Scroll/resize events and observers update geometry without polling.
Rebuilding the list, opening an overlay, losing focus/visibility or pausing the
Android Activity cancels the hold and its glow. CSS and the WebView disable the
main document's standard scrollbar while overflow panels retain their scrolling.

The arrows slide and fade after 440ms of inactivity, with no protruding grip.
The fixed round thumb remains visible while idle; the rail is always invisible.
Only control interaction reveals them; ordinary list scrolling does not. Matrices use full list
width and virtualize groups of 2/3/4/5 square cells, with ellipsized usernames below
each image. Their choice shares the private UI preference store with language.
On cold process initialization, Fetcher disarms saved work without modifying its
queue. Stale/paused service requests and null sticky restarts cannot publish a
new running notification. Explicit Start Missing/Resume can authorize a new run;
ongoing background work continues within its already authorized process.
