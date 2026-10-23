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
Validation workers remain two. Cold checkpoint transactions decrease from
256-record batches to 512-record batches; warm scans keep two transactions.

| Host Chromium, 393×820, real 256 WebP fixture | Original 40px | Phase-2 60px baseline | Plus 60px |
|---|---:|---:|---:|
| 10,000 accounts, render p95 | 4.4 ms | 4.2 ms | 5.5 ms |
| 15,000 accounts, render p95 | 4.0 ms | 4.6 ms | 4.9 ms |
| Frame interval p95 | 16.8 ms | 16.8 ms | 16.8 ms |
| Peak mounted account nodes | 18 | 19 | 19 |
| 15,000-account sampled JavaScript heap | 15.18 MB | 15.22 MB | 15.36 MB |

Render p95 increased by 1.3/0.3 ms against the equal-avatar baseline. We retain
the requested larger avatars, localization and full accessible labels; bounded
DOM/image work and the measured frame interval remain intact. These results do
not establish a scrolling speedup. The virtual-list algorithm was preserved.
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
signed debug artifact. Original debug size was 2,956,131 bytes; Plus is 3,256,037
bytes, approximately 10.15% larger, with
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
