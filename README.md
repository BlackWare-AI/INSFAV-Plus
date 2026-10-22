# INSFAV

**REQUIRED: A JSON backup exported from SamMods InstaPro version 15.30 or earlier. INSFAV is designed to read and display the `favorite_page_pref` (Favorites) section of that backup. Without a compatible backup, the app cannot show your InstaPro favorites.**

INSFAV is a local Android viewer and organizer for the Instagram profiles saved in your InstaPro Favorites backup. It presents the original collections in a fast AMOLED-black interface, with search, account counts, optional local profile pictures and a high-resolution image viewer. It does not automatically access your InstaPro installation or synchronize favorites with Instagram.

## What it does

- Imports the compatible **InstaPro Favorites JSON backup** and preserves the original JSON for export.
- Shows favorite collections, saved profiles, search, collection membership and account statistics.
- Displays local profile photos stored in a user-selected `.thumbnails/<username>.webp` folder using Android's Storage Access Framework (SAF).
- Offers an integrated, optional **Thumb Fetcher 0.4.0** for collecting profile images, with configurable concurrent WebViews, persisted queue, pause/resume, progress notifications and Android-permitted background processing.
- Opens a profile image locally first, then attempts to retrieve the original Instagram image for the in-memory HD viewer, where available. The HD image is not permanently saved.

## How to use

1. In **SamMods InstaPro 15.30 or earlier**, export its settings/backup as a **`.json` file**. The backup must contain the **`favorite_page_pref` Favorites data**. An arbitrary Instagram data export or a newer incompatible backup is **not** a substitute.
2. Open INSFAV and **Import JSON**. Choose the backup. Your Favorites collections and accounts appear.
3. Browse the collections, search for profiles and open their links.
4. **Optional — local avatars:** Select the `.thumbnails` folder in Settings, grant read access, then tap **Rescan Thumbnails**. For a parent folder that contains `.thumbnails`, the app can select the hidden child where supported.
5. **Optional — Thumb Fetcher:** Grant the chosen folder write access, configure Instagram cookies and concurrent WebViews in Fetcher settings, then start the missing-images queue. Observe progress in the application or Android notifications. Instagram authentication, restrictions and connectivity may limit retrieval.
6. Export your collection JSON as a backup whenever needed.

### Read-only thumbnail rescan

Rescan enumerates the folder once to discover added, changed and removed entries. A private SQLite checkpoint stores every candidate, including duplicates and validated unreadable images. Reliable, unchanged size and modification time allow reuse without opening or decoding images. Missing provider metadata requires validation again. Small thumbnails are opened once for bounded header and pixel validation; at most two validation workers run concurrently. Completed 256-file batches survive interruption; retry re-enumerates metadata and reuses saved work. The existing JSON index remains available until a full scan finishes. Rescan never writes, renames or deletes source files, even with write permission. See [rescan measurements and limitations](docs/RESCAN-PERFORMANCE.md).

## Requirements and privacy

- Android 8.0 (API 26) or newer.
- The compatible InstaPro `.json` backup described above is **mandatory for viewing favorites**.
- Local image folders are user-selected; scanning is read-only. The Fetcher writes only when explicitly granted access.
- Original source thumbnails are not re-encoded into a second private thumbnail archive. WebView may temporarily cache original responses.
- HD photos are kept in memory, not saved by INSFAV.
- The Fetcher depends on Instagram availability and account access; neither live retrieval nor uninterrupted background work is guaranteed. Android Force Stop and operating-system restrictions still apply.
- INSFAV is an independent project and is not affiliated with Instagram, Meta or SamMods.

## Open source and development

This public GitHub repository may be **forked** to study, modify and extend the project, subject to its license and any applicable third-party terms. Contributions can be proposed by pull request. Do not commit private JSON backups, cookies or signing keys.

The stable source history is retained in Git. A separately named pre-4.0.0 backup branch preserves the previous mainline code. Tagged 4.0.0 source and binaries should be published only after verification and release signing.

Build using **JDK 17**, Android SDK Platform 35 and the Gradle wrapper:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease
```

The `release` build requires the original private signing key to produce an installable, update-compatible APK. Configure `INSFAV_KEYSTORE`, `INSFAV_STORE_PASSWORD`, `INSFAV_KEY_ALIAS` and `INSFAV_KEY_PASSWORD` locally or as secured CI secrets. **Never publish the keystore or passwords.** Unsigned APKs are not an installable public release, and replacing an installed APK signed with another certificate is not a safe in-place upgrade.

For the guarded GitHub Actions publication workflow, required secret names, certificate pinning and verification steps, see [Official release signing](docs/RELEASE-SIGNING.md). Missing configuration blocks publication; the workflow never generates a replacement key or claims compatibility with an unverified previous APK.

Source files: `web/` (UI), `app/src/main/java/` (Android, SAF and Fetcher), `app/src/test/` and `tests/` (verification). Web bundles in `app/src/main/assets/` are generated via `npm ci --ignore-scripts && npm run web`.

For technical verification and limitations, see [Thumbnail verification](docs/THUMBNAIL-VERIFICATION-TR.md) and [Fetcher integration](docs/FETCHER-040.md).
