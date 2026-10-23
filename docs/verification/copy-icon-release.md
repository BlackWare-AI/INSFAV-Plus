# 1.1.3 / build 5: larger Missing Images copy icon

Only the copy glyph inside the existing Missing Images metric is enlarged. Its
SVG viewport grows from 14×14 to 28×34px and shifts down 3px. The same two-sheet
symbol stretches vertically; the button stays 44×44px and its card, count, label,
spacing and copy/disabled behaviors are preserved.

A temporary Chromium geometry probe compares the original and new icon styles
at 320/393/480px widths with 100%/200% text. Card, button, count and label bounds
are identical; the enlarged icon extends further down and remains entirely
inside the existing button. The actual clipboard integration checks pass,
including unique missing usernames, rejected clipboard writes and empty lists.
The 17 core JS checks also pass. No extra permanent test is added for this small
visual change. The existing browser check now bounds the icon by its button,
instead of limiting it to the counter font size; paired-card and overflow
checks remain. Current-source CI/emulator gates precede signed publication.

VersionCode increases from 4 to 5 using the same Plus signing certificate.
Physical-device behavior remains unverified.

Published: [INSFAV Plus 1.1.3 / build 5](https://github.com/BlackWare-AI/INSFAV-Plus/releases/tag/plus-v1.1.3-build.5).
The updated browser check, full Chromium suite, Android unit tests, lint,
assembly and emulator gates passed. The verification job was retried after
slow Ubuntu dependency downloads. The published APK was downloaded again and
its bytes/metadata match the verified local file. Public source, workflow,
certificate and checksum records are in release-1.1.3.json.
