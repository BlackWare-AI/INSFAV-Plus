# 1.1.6 / build 8: newest-first Fetcher and no homepage gate

Only Fetcher runtime code changes: eligible account selection orders by descending
source position, including unattempted interrupted claims. The source JSON,
main list, result ordering, image files, completed statuses and explicit retry
history retain their existing order and behavior. New favorites appended to the
source are first after sync; re-opening the durable queue does not undo this.

Compared the published standalone Thumb Fetcher v0.5.0/build 9 source at
401631af687a01cd062e6ee7169b0f576c751759, read-only. Its QueueEngine dispatches
profiles without calling authenticate(). Plus already contains its unavailable
text fast-skip behavior, with a candidate veto and stronger wall precedence.
Replacing that probe is unnecessary. File hashes are in
../fetcher-050-comparison.json.

A separate Plus-only homepage preflight was a concrete queue-wide stall path:
before fetching any requested account, FetchBroker synchronously called
provider.authenticate(). Missing viewer evidence or a homepage network timeout
could return COOKIE_AUTH_UNVERIFIED, install a 60-second retryAt and end the
whole batch. Repeating Start/Resume repeated that unrelated request. Bulk
retrieval now starts at the requested profile, as in standalone 0.5.0. Per-profile
cookie installation and actual login/cookie-expiry/challenge/429 barriers remain.
No session is marked VERIFIED merely because a public profile was fetched.
Cancelled cookie setup poisons the provider so Resume opens a clean instance.
Pacing, memory limits, deadlines, manual start and background restrictions remain.

The code path above is reproducible with offline providers. Without a device
log or the exact displayed failure, it cannot establish that every user-reported
pause was this cause; real rate limits, challenges, storage and Android limits
can still legitimately pause work. No live Instagram session or physical device
was tested.

Regression coverage checks newest-first order across reopen, appended favorites,
deduplication, interrupted claims and held failures; direct profile dispatch
across batches despite a homepage timeout; cancellation during profile setup;
resume and existing-image reuse; explicit login-wall stopping; rate limits;
account-local unavailable/timeouts continuing the batch. Existing fault fixtures
now express their intended dispatch sequence in oldest-first source order.
The JS core and 37 offline probe-classification cases pass. All 177 native Fetcher tests pass with no failures/errors.
Full current-source CI gates precede signed release publication. UI source
and packaged assets are unchanged.
