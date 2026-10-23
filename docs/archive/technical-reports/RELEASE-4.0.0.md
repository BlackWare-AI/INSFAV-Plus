# INSFAV 4.0.0

**Requires a JSON backup from SamMods InstaPro version 15.30 or earlier, containing the `favorite_page_pref` Favorites section. An arbitrary Instagram export is not a compatible replacement.**

INSFAV displays your saved Instagram account collections in an AMOLED-black Android interface. Import your compatible backup using **Application Information → Add List**, then browse collections or search accounts. Use **Settings → Select Thumbnail Folder** to connect an optional `.thumbnails` folder.

## Changes

- Incremental **Rescan Thumbnails** reuses validated image metadata when the selected SAF provider reports unchanged, reliable file size and modification time.
- Added, changed and removed files are reconciled. Missing metadata, malformed images and duplicate candidates are revalidated for safety.
- Rescan still enumerates the folder to discover changes; it does not claim to avoid every SAF directory query.
- Original JSON, collection memberships, usage/load/reset counters and source images are preserved by rescan.
- Local avatars, the memory-only HD viewer, integrated Thumb Fetcher 0.4.0 and the existing AMOLED interface remain available.
- Application Information contains Add List and Delete List; Search fills the remaining bottom bar.

## Use and limitations

Android 8.0 or newer is required. Thumb Fetcher is optional, requires a valid user-provided Instagram session and respects its configured schedule and access restrictions. Android and device-specific limits can interrupt background work. Live Instagram availability is not guaranteed.

Providers that incorrectly report unchanged metadata cannot guarantee detection of overwritten photos. Reselect the folder or update the source file timestamp if necessary. No user JSON, cookies or signing keys are included in this release.

Automated verification covers Android unit tests, incremental rescan cases and release-signing gates. Physical-device and live Instagram results are not implied by these tests.

INSFAV is independent and is not affiliated with Instagram, Meta or SamMods. Developer: BlackWare. AI development assistance: OpenAI ChatGPT.
