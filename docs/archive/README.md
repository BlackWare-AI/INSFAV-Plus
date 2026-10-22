# Historical material

`technical-reports/` contains the unmodified original release and implementation
reports. Their version numbers, old measurements and publication instructions
are historical, not instructions for Plus. `legacy-workflows/` and
`legacy-tools/` are inactive archives; do not execute their signing/release tools.
Dependency review found them used only by the archived old workflows, their own
Python tests and old reports. Current build/test scripts do not import them.

Root APKs and version-specific release assets are not tracked in the active Plus
tree. Exact source history (including those binaries) is in `source/`'s Git bundle;
`release-artifacts.json` preserves their byte sizes, SHA-256 values and verified
public signing certificate digests. Future distribution uses Plus GitHub Releases
or clearly marked debug CI artifacts.
