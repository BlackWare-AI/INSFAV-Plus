# Main-list scroll controls

This records the initial 1.0.0 controls. The 1.1.0 update is documented in
[matrix-release.md](matrix-release.md), including the one-second hold and idle UI.

Scope: `BlackWare-AI/INSFAV-Plus` only. The existing choice dialogs and launcher
artwork from PR #3 are retained and verified again. `web/` remains the source of
the packaged UI, including the new `list-scroll.js` controller and source map.

The whole main document scrolls, including every expanded collection. Its fixed
48px strip has 12px clearance from the rows, at least 15px from the screen edge,
and ends 12px above the bottom navigation. Account rows remain 65px and avatars
60×60px. The arrows immediately jump to zero or the actual document maximum and
disable at their respective bounds. The thin thumb derives its length and
position from actual content/viewport dimensions, with an 8px visible minimum
and a separate target of at least 48×48px. Its visible mark reaches both ends of
the track. Empty or completely collapsed account lists hide the controls even
when closed folder headings overflow the screen.

Only dragging waits three seconds. Activation anchors to the current pointer
and scroll positions without moving the list. Release, more than 8px movement
before activation, pointer cancellation/capture loss, multi-touch, focus or
visibility loss, Android Activity pause, overlays, list rebuilds and geometry
changes cancel the hold and glow. The empty track and short presses never
scroll. The list still supports normal touch scrolling. Arrow keys, Page Up/Down,
Space/Shift+Space and Home/End work immediately on the scrollbar. Its name,
instructions and position are available in Turkish and English. Geometry updates
use scroll/resize events and observers, with no idle polling.

`tests/list-scroll.cjs` covers 10, 1,000, 10,000 and 15,000 accounts, actual bounds
and thumb ratios, folder/search/filter changes, all seven filter choices with
search, exact 2,999/3,000ms timing, every cancellation path, glow, keyboard focus,
and Chromium's trusted touch input/capture. It checks 320×480, 360×640, 393×850,
480×640, 480×320 and 850×393 viewports with 200% text, including strip clearance,
fixed row/avatar sizes and 48px targets. Virtual rows stay below 40, image work
at most six concurrent loads and at most 64 retained entries. An idle virtual
clock check verifies that controls do not schedule continuous animation frames.

The existing choice-dialog, language and filter suites cover Turkish/English,
system language, saved choices, refresh, selection/cancellation and focus return.
They also check 320–480px widths and 200% text, a scrollable option list, the
absence of images from About, and the launcher's references and original logo
bytes. `MainListLifecycleTest` checks that Android pause sends the cancellation
event to the Web UI.

Physical-device verification remains outstanding: Android WebView touch timing
and glow, OS/predictive Back, lifecycle transitions, TalkBack announcements and
launcher rendering. Chromium's Back handler calls and trusted touch input, and
Robolectric's Activity lifecycle, do not establish those physical-device results.
No live Instagram test or official release is part of this change.

## Local validation, 2026-10-23

- `npm run web:check`: generated assets, maps and language resources reproduce.
- `npm test`: 16 JavaScript tests pass.
- `CHROME_BIN=/usr/bin/chromium npm run test:chromium`: all 11 groups pass,
  including 278 scroll checks, 217 choice-dialog checks and 12 avatar matrices.
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`:
  273 tests in 47 suites pass, with no skipped/failed tests; lint reports no
  issues. Lint and assembly pass again after the final web regeneration.
- APK signature/package verification passes for `com.instagram.insfav.plus`,
  version `1.0.0-debug` (code 1), 3,104,466 bytes. Every packaged web asset matches
  its generated source, and the APK's drawable logo matches the supplied JPEG.
  APK SHA-256: `4c59edaf5c0a67ff84b392b4dc4780837190ea697fb664edf421693d155808d8`.
- The optional performance runner completes its general and acceptance scripts;
  its thumbnail script hits the runner's 120-second timeout. Running that same
  thumbnail script directly completes all 18 baseline/current samples across
  5,000/10,000/20,000 accounts. Current samples retain at most 26 account nodes,
  64 image entries and 31 recycled rows; all concurrency/resource assertions pass.
  These synthetic host measurements do not establish Android device performance.

Logo SHA-256 remains
`3115aec40d4bd59031c5bd56448ccb50f0deb5b71e3dcb299532012c37a19ef3`.
