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
visual change. Current-source CI/emulator gates precede signed publication.

VersionCode increases from 4 to 5 using the same Plus signing certificate.
Physical-device behavior remains unverified.
