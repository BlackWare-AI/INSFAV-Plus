# 1.1.5 / build 7: frosted scroll ball and compact cards

The fixed 40px ball now has a translucent interior with a very light 1.5px
backdrop blur. Its rail remains invisible. A tap slides the ball fully into its
existing position without moving the list. After 3 seconds without interaction,
it slides right until exactly half remains visible at the physical screen edge.
The arrow idle delay also increases from 440ms to 3 seconds. The existing 180ms
transition respects reduced motion; keyboard focus and active dragging keep the
controls exposed. The drag hold stays 250ms, with existing cancellation rules.
No polling or layout space is added. Chromium can lose underlying glyphs when
a backdrop filter moves offscreen, so blur is disabled before docking and
restored only after reveal completes; the half-exposed ball stays translucent.
Active/docked screenshots were inspected to confirm list text remains visible.
A pixel-based browser regression check confirms that docking preserves rendered
account text, allowing normal antialiasing differences between compositor layers.

The owner's annotated reference approves the compact Total / Unique cards.
All six basic settings cards now use that compact size, instead of matching
the previously taller Missing Images row. The same enlarged copy glyph and
44px button are positioned inside the card without contributing extra height.
Space is reserved to keep the text and icon separate. Intrinsic equal rows can
grow together for narrow screens or larger text, without clipping content.

Local validation: 335 scroll checks cover 10/1000/10000/15000 accounts, real
bounds/ratios, 249/250ms drag activation, 2999/3000ms idle boundaries, tap reveal
without scroll, half-exposed docking, repeat timeout, trusted touch, cancellation,
keyboard, languages, layouts, no overflow and bounded DOM/image work. The
127 browser checks, 17 core JS checks, settings-language check and actual
clipboard integration pass. A temporary geometry probe checks all six cards
at 280/320/393/480/600px, 100%/200% root fonts and TR/EN, using five-digit
counts and a long folder path (20 combinations). At normal text and widths
393px or greater, the approved compact top row is the height reference; larger
or narrower layouts grow all rows equally. Every child and copy icon stays
inside its card. Active/docked controls and compact settings were visually
reviewed from Chromium screenshots. Bundles are regenerated from web/.

Current-source CI gates precede signed release publication. VersionCode rises
to 7, using the existing Plus certificate. Physical-device behavior, including
Android edge gesture interaction and WebView blur rendering, is unverified.

Published: [INSFAV Plus 1.1.5 / build 7](https://github.com/BlackWare-AI/INSFAV-Plus/releases/tag/plus-v1.1.5-build.7).
Full Chromium, Android unit tests, lint, assembly and emulator gates passed.
The published APK was downloaded and verified against local bytes and metadata.
See release-1.1.5.json for source, CI, publication, certificate and checksum records.
