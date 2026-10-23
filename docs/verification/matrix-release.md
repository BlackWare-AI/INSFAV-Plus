# 1.1.0 / build 2

Only INSFAV-Plus changes. The application information modal and its actions are
unchanged. The original list keeps its row, avatar and horizontal geometry.

Scroll controls overlay the full-width list with a 6px edge inset. Arrows have
transparent backgrounds and no frames; arrows, rail and thumb slide/fade after
440ms of inactivity. There is no protruding grip. The thumb target remains
reachable while idle, and only interaction with the controls reveals them.
Keyboard focus and active holds keep them visible. Dragging requires exactly
250ms; cancellation and clamping remain. Folder surfaces are pure black.
The small-height fallback hides controls when 52px arrows and a 56px thumb target
would overlap bottom navigation.

Settings begin with an expandable view-mode section: original List or 2/3/4/5-column
Matrix. Native private preferences persist the choice. Each expanded collection
groups its accounts into virtual matrix rows. Images remain square, with the
existing 18px rounding and only an ellipsized username below. Usernames open
profiles, and images use the existing viewer. Matrices preserve collection order,
membership, search and result filters, with bounded image loads/retention.
The existing language choice moves to the upper-right settings toolbar.

Fetcher initialization disarms saved work and cancels old scheduler jobs while
preserving queue records. Workers require an initialized process and a manual
start; paused/stale service intents and null sticky restarts cannot launch a
notification. Service/worker starts no longer clear a newer Pause/Stop request.
An explicitly authorized run can continue in the background in that process;
after process restart, Start Missing or Resume is required again.

Local checks:

- 17 core JavaScript tests pass, including matrix grouping/order.
- 114 matrix Chromium checks pass: 2/3/4/5 columns, narrow/landscape geometry, 200%
  text, persisted choice, folders/search/filter, profile/viewer and language UI.
- 217 choice-dialog Chromium checks pass, including launcher-source byte equality.
- 297 scrollbar Chromium checks pass using Playwright's bundled Chromium:
  249/250ms activation, removed grip, 439/440ms idle visibility, cancellation and real touch.
  Width changes are rechecked at activation even before the resize event arrives.
- All 277 Android unit tests pass, including manual startup, stored view choice,
  queue preservation and notification lifecycle. Lint reports no issues;
  unsigned release assembly is checked locally. The signed publish workflow remains the
  authority for an official release artifact.

No physical-device testing was performed. Chromium, Robolectric and host builds
do not establish device-specific WebView, notification or lifecycle behavior.

Current UI refinements also match the results selector's outer shell and text to
the bottom navigation, remove the matrix header divider, and enlarge the new
missing-photo portrait glyph from 34px to 40.8px on black. The replacement
1280×1280 launcher source is preserved byte-for-byte (see branding.json); its
foreground bounds are square. The themed launcher uses a matching star-plus silhouette
in place of the unrelated legacy badge.

## Published release

[INSFAV Plus 1.1.0 / build 2](https://github.com/BlackWare-AI/INSFAV-Plus/releases/tag/plus-v1.1.0-build.2)
is published with the owner-authorized permanent key. Current-source CI and
emulator checks and the signed-file publication job passed. The public APK was
downloaded again and its checksum/metadata match the verified local artifact.
See release-1.1.0.json for immutable source/workflow references and public
certificate/checksum records. Signing material is only in the private owner
backup; it is absent from Git and public artifacts.
