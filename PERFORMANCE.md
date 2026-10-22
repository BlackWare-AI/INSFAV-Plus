# Performance evidence

Measurements are offline synthetic host tests, not live Instagram or physical
Android certification. Source: `0a4ba915418e0652618879d4af2c0745ef9ca4f0`.
Toolchain: JDK 17, Robolectric API 28/34, Node 22, installed host Chromium.
The test harness and exact final results are retained under `docs/verification/`.

| Workload | Original | Plus (phase 4) | Scope |
|---|---:|---:|---|
| 64 updates, 15,000 initial profiles: full JSON writes | 64 | 1 | Host AtomicFile |
| Serialized snapshot bytes | 154,660,287 | 2,421,238 | Excludes SQLite journal/WAL and physical block amplification |
| Metadata update elapsed | 1,221.3 ms | 289.0 ms | Single runs; not a stable device speed claim |
| Idle dispatcher wait transitions in ~1 second | 50 | 0 | Host JVM thread |
| Idle dispatcher CPU in same samples | 3.096 ms | 0 measured ns | Zero is measurement resolution, not proof of zero device power |

Existing tests measure cold/warm scans of 15,000 real synthetic 128/256 WebP
files, fingerprint contents and dates, and enforce zero source writes. Warm scan
requires zero source opens. Scan status records query, open, validation/decode,
transaction and JSON time; decode includes source reading. Two validation workers
remain fixed; worker count was not increased as an assumed optimization.

Browser tests cover 10/1,000/10,000/15,000 accounts, 320/393/480px widths,
60px images/65px rows, large text, missing images, folder toggles, search updates,
fast scroll and navigation clearance. Measured host-browser render/scroll/image
latency results must be interpreted separately from Android FPS and native RAM.
No large bitmap LRU was added. Device RAM peak, Android idle power and real SAF
provider timings require device measurements; unavailable values are labeled.

Inter optimization preserves all original Unicode cmap entries, glyph advances
and outlines and checks Turkish coverage. Browser pixel comparisons protect
shaping at three weights. Exact font byte changes are in verification artifacts.
