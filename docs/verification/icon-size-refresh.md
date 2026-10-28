# Launcher icon size refresh

The existing star and plus artwork was enlarged by 6.7% with a centered 480×480
crop resized to the same 512×512 canvas. Its artwork, proportions, colors and
black background were retained. `npm run web` regenerates the Android copies.

The official same-build release is documented in
[`release-1.1.16-icon-size.json`](release-1.1.16-icon-size.json). It retains
version 1.1.16, build 19 and the current Plus signing certificate. Its verified
APK differs from the preceding build 19 APK only in the two icon payloads and
source revision metadata. The full browser, JVM, lint, assembly and CI emulator
checks passed. Physical-device launcher behavior was not tested.
