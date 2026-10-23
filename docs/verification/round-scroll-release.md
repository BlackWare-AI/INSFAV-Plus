# 1.1.1 / build 3: restored placeholder and fixed scroll ball

The original outlined person silhouette replaces the framed portrait introduced
in 1.1.0. It occupies 48px inside the unchanged 60px list avatar; matrix cells
limit it to 85% of their size so small tiles remain unclipped.

A fixed 40px gray/black circle replaces the proportional scrollbar marker. Its
52×56px touch target and circle size stay constant for all content lengths. The
ball remains visible whenever the opened account list overflows, including when
idle, so it can be found and grabbed. The rail is always invisible. Arrow
buttons retain their transparent style and 440ms fade/slide behavior. The
250ms hold, movement cancellation, actual range mapping, keyboard operations,
virtual rows and unchanged list width are retained.

Local verification covers 17 JS tests and 318 scrollbar Chromium assertions
across 10/1,000/10,000/15,000 accounts, real touch, 249/250ms boundaries,
cancellation, keyboard, fixed circle size, idle visibility and hidden rail.
The matrix checks verify the restored SVG and its larger list size as well as
2/3/4/5-column layouts, narrow/landscape screens and 200% text.

The release reuses the permanent Plus certificate documented in RELEASE.md,
with versionCode increasing from 2 to 3. Current-source CI/emulator gates and
signed-download verification are required before publication. Physical-device
behavior remains unverified.
