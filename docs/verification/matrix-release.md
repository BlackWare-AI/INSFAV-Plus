# 1.1.0 / build 2

Only INSFAV-Plus changes. The application information modal and its actions are
unchanged. The original list keeps its row, avatar and horizontal geometry.

Scroll controls grow to 52px arrows with 28px icons, a 12px-wide visible thumb
and a 15px grip extending left. After 2.2 seconds of inactivity the arrows, rail
and thumb fade, leaving the grip available. Invisible controls allow underlying
matrix images to receive taps. Keyboard focus and an active hold keep controls
visible. Dragging requires exactly one second; cancellation and clamping remain.
The small-height fallback hides controls when 52px arrows and a 56px thumb target
would overlap bottom navigation.

Settings end with an expandable view-mode section: original List or 2/3/4-column
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
- 75 matrix Chromium checks pass: 2/3/4 columns, narrow/landscape geometry, 200%
  text, persisted choice, folders/search/filter, profile/viewer and language UI.
- 284 scrollbar Chromium checks pass using Playwright's bundled Chromium:
  999/1,000ms activation, 15px grip, idle visibility, cancellation and real touch.
  Width changes are rechecked at activation even before the resize event arrives.
- 23 relevant Android tests pass: manual startup, stored view choice, queue
  preservation and notification lifecycle. Lint reports no issues; debug and
  unsigned release assembly pass. The signed publish workflow remains the
  authority for an official release artifact.

No physical-device testing was performed. Chromium, Robolectric and host builds
do not establish device-specific WebView, notification or lifecycle behavior.
