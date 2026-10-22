# Original INSFAV → INSFAV Plus

1. Keep original INSFAV installed. Export its collection JSON and, optionally,
   its password-protected settings archive through that application's menus.
2. Install **INSFAV Plus** (`com.instagram.insfav.plus`). It uses a separate
   Android UID and does not update or uninstall `com.instagram.insfav`.
3. In Plus **Settings → Backup / Restore → Import JSON**, choose the old JSON.
   Arrays and `favorite_page_pref` wrappers (array or encoded JSON) are supported.
   Unknown fields, order and membership in multiple lists remain unchanged.
4. Select your existing `.thumbnails` folder through Android's folder picker.
   Run **Rescan Thumbnails**. 128/256 WebP and other supported sources are read
   directly, without copying, encoding or changing their dates. Cancel preserves
   completed checkpoints; rerun resumes unchanged files without reopening them.
5. To migrate encrypted settings, explicitly choose **Import settings from
   original INSFAV** and enter the archive password in the native secure dialog.
   This accepts only `INSFAV_SETTINGS` version 1 with application
   `com.instagram.insfav`, including complete GCM verification. Normal import
   accepts only the current Plus package. Arbitrary package fallback is rejected.
6. Re-select the source folder if Plus does not hold its own SAF grant. Review
   worker count, working hours, language, cookies and optional write permission.
   Imported cookies are encrypted with Plus's own Android Keystore key. Retrieval
   stays stopped; use Start Missing/Resume deliberately inside the configured hours.

The default working window is 00:00–08:00 in local 24-hour time. Overnight
windows are supported. Existing saved schedules are retained. Force stopping an
app prevents any guarantee of automatic background restart.

Collection JSON and encrypted settings remain separate formats. Neither app's
private database, preferences, cookie file or Keystore is accessed by the other.
Orphan thumbnails remain available for read-only inspection and are not deleted.
Existing invalid source filenames are preserved and reported as
`OUTPUT_EXISTING_PRESERVED`; choose another authorized folder or handle them
outside the app before retrying.
