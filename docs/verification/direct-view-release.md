# 1.1.2 / build 4: direct view-mode box

The first settings row is now an always-visible, labeled view-mode selection box.
There is no details/summary disclosure and no preliminary expand action. Tapping
the box opens the existing shared choice dialog with List and 2/3/4/5-column
Matrix options; selection, native persistence and language handling are retained.
The information modal and Advanced / Diagnostics section are unchanged.

The matrix Chromium checks cover direct selector visibility, the missing
disclosure, visible label, persisted selection of every mode, Turkish/English,
and a 48px-or-larger target without overflow at 320px and 200% text. Core JS and
current-source CI/emulator gates are required before publishing the signed APK.
VersionCode increases from 3 to 4 and the permanent Plus signing key is reused.
Physical-device behavior remains unverified.

Published: [INSFAV Plus 1.1.2 / build 4](https://github.com/BlackWare-AI/INSFAV-Plus/releases/tag/plus-v1.1.2-build.4).
All current-source CI and emulator gates and the signed publication workflow
passed. The public APK was downloaded again and its bytes and metadata match
the verified local artifact. The certificate is unchanged. Public source, CI,
publication and checksum records are in release-1.1.2.json.
