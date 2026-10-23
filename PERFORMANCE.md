# Performance evidence

Original: `0a4ba915418e0652618879d4af2c0745ef9ca4f0`. Same host, JDK 17,
Robolectric API 28/34, 2 CPU quota / 8 GiB memory limit, no emulator during paired
host measurements. Coverage agent disabled in both variants. Three repetitions;
tables use medians. Raw numeric results are in [docs/verification/performance](docs/verification/performance).
These are offline fixtures. No physical-device or live Instagram results are claimed.

| Workload | Original | Plus | Interpretation |
|---|---:|---:|---|
| 64 metadata updates with 15,000 initial profiles | 1,111 ms | 266 ms | ~76% lower elapsed time |
| Full public JSON snapshots | 64 | 1 | Same resulting catalog; durable per-image journal retained |
| Snapshot serialized bytes | 154,660,287 | 2,421,238 | ~98.4% lower public snapshot content |
| Linux process `wchar`, including SQLite journal | 154,660,287 | 3,615,158 | ~97.7% less content written |
| Linux process write calls | 64 | 1,021 | More small durable SQLite writes; fewer bytes overall |
| Idle dispatcher waits / ~1 second | 50 | 0 | Host JVM wait transitions, not Android power |
| Idle dispatcher thread CPU | ~3 ms | 0 counted ns | Zero is counter resolution, not proof of zero CPU/power |
| 20 unchanged resume responses, 15,000 profiles | 24,992,020 bytes | ~18,680 bytes | Full profiles omitted when revision/scope is unchanged |
| Same resume response workload | 740 ms | 66.6 ms | ~91% lower host processing time |
| Response after one profile changes | 1,249,601 bytes | ~1,061 bytes | Metadata patch rather than full profile map |
| 2.2-second progress stream plus 1.1-second drain | 11 deliveries | 3 deliveries | About 1 Hz with a final trailing update |
| Two back-to-back critical updates | Second suppressed | Both immediate | Pause/Stop/error/completion bypass the normal limiter |

The host filesystem is memory-backed: kernel `write_bytes` was **0 in both
variants**. `wchar` measures submitted content, not physical NAND writes or flash
wear. The journal increases write-call count to preserve every successful image
through interruption; the public JSON/SQLite schemas remain unchanged. Snapshot
and recovery tests cover journal-before-memory, half AtomicFile writes, replay
watermarks and full replacement.

Cold metadata scans of 15,000 actual synthetic 128/256 WebP files (1 ms simulated
SAF-open delay) measured 12,653 → 12,348 ms. Warm scans measured 467 → 443 ms,
but Plus ranged 340–563 ms versus 449–501 ms originally. **No robust warm-scan
speedup is claimed.** Both warm variants opened zero source files. Source bytes
and modified dates were fingerprinted; application writes to sources were zero.
Validation workers remain two. Those historical Plus samples used 512-record batches; warm scans kept two
transactions. The subsequent controlled batch review below retains 256 in the
final application.

| Host Chromium, 393×820, real 256 WebP fixture | Original 40px | Phase-2 60px baseline | Plus 60px |
|---|---:|---:|---:|
| 10,000 accounts, render p95 | 4.0 ms | 4.1 ms | 4.2 ms |
| 15,000 accounts, render p95 | 4.7 ms | 4.5 ms | 4.0 ms |
| Frame interval p95 across dataset medians | 16.7–16.8 ms | 16.7–16.8 ms | 16.8 ms |
| Peak mounted account nodes | 18 | 19 | 19 |
| 15,000-account sampled JavaScript heap (decimal MB) | 15.24 MB | 15.24 MB | 15.43 MB |

The final owner-review trace includes full-text long-press/keyboard access and
the new logo. Render p95 differs by +0.1/−0.5 ms from the equal-avatar baseline
for 10k/15k records; these small mixed changes do not establish a scrolling
speedup. Bounded DOM/image work and the measured frame interval remain intact. The virtual-list algorithm was preserved.
Cold bootstrap timings varied with dataset/JIT behavior, so no startup-speed
claim is made. A deferred display-name-index experiment was reverted after mixed
results. Hoisting Turkish query normalization once per query reduced measured
15,000-name searches from 12.9/19.1 ms to 3.2/1.4 ms (first/second query), while
keeping Instagram identity normalization unchanged.

The original thumbnail performance harness separately records image load time,
request counts, queue cancellation, retained images and scrolling for 5,000, 10,000 and
20,000 records. It and the acceptance matrix keep at most six active browser
image loads, 64 retained image entries and fewer than 32 mounted account nodes.
The sampled JavaScript heap excludes native bitmaps, GPU allocations and Android
renderer processes. Encoded native responses share bounded chunks and release
on close; no native bitmap LRU or new thumbnail disk cache was added. A total
Android RAM-peak reduction has **not** been measured.

API 35 emulator evidence at commit `85cb932` uses a real Binder SAF provider:
15,000 files scanned in 27,344 ms cold / 1,426 ms warm; 15,000 / 0 source opens,
two validation workers, 512-record batches, zero source writes and unchanged
bytes/dates. App PSS samples were 55,164 KiB before and 216,759 KiB after; these
are samples, not a continuous peak. These Android values are not compared as
speedups against the host results. Query, cursor, source-open, validation/decode,
SQLite and JSON timings are retained in
[the emulator scan report](docs/verification/emulator-api35/rescan-15000.json).
Worker durations overlap and must not be summed as wall time; decode includes
source reading. Final CI acceptance and coexistence are linked from the PR.

The final debug APK metadata is in [docs/verification/debug-apk.json](docs/verification/debug-apk.json).
This JSON describes the locally signed test APK; CI verifies its own separately
signed debug artifact. Original debug size was 2,956,131 bytes; Plus is 3,092,065
bytes, approximately 4.60% larger, with
bilingual resources, inventory and recovery functionality. There are no new
runtime dependencies and no JaCoCo agent in the APK. Debug and old minified
release APK sizes are not treated as comparable builds.

Reproduce with `python3 tools/benchmark.py --kind all --rounds 3`. It restores the
preserved baseline locally and adds external test harnesses without editing its
tracked files. Run full verification and JaCoCo again after filtered benchmark
runs. Physical-device RAM, idle power, slow provider behavior and live-service
latency remain unverified.

The optional historical `browser-render.json` trace used the old single-process
launch flags. Those flags were removed from the harness after CI system Chrome
failed before page load. The main three-variant acceptance table above already
uses normal multiprocess launches. The launch-failure path now closes its HTTP
server and exits nonzero rather than keeping the test process alive.

## Owner safety review: policy comparisons

[Batch evidence](docs/verification/performance/batch-size-comparison.json) compares
the **same Plus scanner** at 256 and 512 on the same 15,000 real synthetic 128/256
WebP files, two workers, 1 ms simulated source-open delay, three counterbalanced
pairs, no coverage agent. Cold medians are **11,136.52 ms (256)** and **11,166.32 ms
(512)**. Warm medians are **149.35 ms (256)** and **182.78 ms (512)**. SQL transaction
medians improve slightly at 512 (69.17→63.05 ms), but total scan time does not.
The default is therefore **256**. Warm source opens are zero in both variants;
source contents/dates are unchanged and source writes are zero. This isolates
scanner/batch behavior on a memory-backed host, not a phone-wide speed claim.

[Deadline evidence](docs/verification/performance/deadline-comparison.json) drives
the actual provider watchdog and late callback decision with a Robolectric virtual
clock. Each threshold sees the same eight known-valid delayed callbacks at
2/8/14/16/22/29/31/44 seconds, through both normal and HD paths (16 observations).
15 seconds falsely times out 10/16, 30 seconds 4/16, and 45 seconds 0/16. These
are deliberately chosen boundary/slow fixtures, **not a live failure-rate sample**.
The conservative original **45-second** limit is retained; every expiry remains
WEBVIEW_TIMEOUT and retryable. Fast confirmed unavailable profiles still skip
immediately. No live evidence justifies shortening this baseline.

Before retaining batched index snapshots, all six existing journal/AtomicFile
recovery tests were rerun successfully. A seventh regression acknowledges 63
updates below the snapshot threshold, stops the in-memory timer, reopens the Store
and verifies all 63 records and their counts, durable snapshot, empty journal and
idempotent second restart. This models interruption, not a physical power cut.

APK size is now compared using clean builds: original 2,956,131 bytes versus final
Plus 3,092,065 bytes (+4.60%). Earlier incremental packaging checkpoints
(3,256,037 / 3,326,545 bytes) retained additional dex packaging overhead and are
superseded for the size comparison. The clean APK has no duplicate class
definitions or embedded JaCoCo agent; its own metadata/checksum is retained.
