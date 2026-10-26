The owner approved using the supplied signing key for this release. Its certificate differs from previous releases, so this APK cannot update those installations in place. Uninstalling clears app-private sessions, queues and settings: export or back up needed data with the existing app features before choosing to reinstall. Future releases are pinned to the approved signing identity.

Persistent WebView Session is the default Instagram sign-in method. A visible in-app browser lets the user complete login, 2FA and Instagram security checks. Verified browser cookies are flushed immediately and at lifecycle boundaries, with no startup or provider-close purge.

Signing out clears this app's Instagram browser session while preserving manual cookies and other app data.

The thumbnail engine reuses this app's browser session and validates signed-in page evidence. Login/security failures stop new requests, cancel active transfers and persist a verification hold. Completing verification does not resume the queue automatically.

Manual Netscape cookies.txt text, HTTP Cookie header paste, Save, Test and Remove remain available. Manual cookies stay AES-GCM encrypted and use an isolated browser profile, preserving the WebView login. Isolated manual browsing requires Android System WebView with MULTI_PROFILE support; an unavailable profile never overwrites the saved WebView login.

Automated checks use synthetic data and offline pages. Real Instagram login, account-specific 2FA/security flows, server-side expiration/revocation and physical-device Android memory reclamation have not been tested. Instagram can expire or revoke any server-side session; local persistence cannot prevent that.
