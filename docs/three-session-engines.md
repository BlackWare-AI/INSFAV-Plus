# Selectable Instagram browser paths

Settings retains Manual Cookies (paste/save/remove) and adds three persistent-browser engines:

- Current: CookieManager-authoritative extraction with optional Chromium metadata, encrypted snapshot, HTTP profile resolver.
- YTDLnis-style: own default WebView SQLite rows, Chromium expiry conversion, in-memory Netscape serialization, existing saved-cookie parser and HTTP resolver. Separate encrypted snapshot. If Chromium encrypts a value, the supported CookieManager API supplies it. An unavailable database reports an extraction failure rather than silently becoming the current extractor.
- Legacy persistent WebView: the existing WebView profile/HD resolver used by the old cookie-based engine, using the default persistent browser directly. No cookie removal, injection or MULTI_PROFILE requirement in this mode. It opens the requested profile without a home preflight. This preserves later HD/security fixes rather than restoring old files wholesale.

All three share this application's persistent first-party login. Switching does not erase cookies, manual records, files or the queue. Changing modes pauses bulk; it does not bypass an existing security hold. The visible capture button resolves a completed user login; it performs no network cookie test. No automatic login-page verification loop or mandatory pre-download authentication request remains. Actual requests still stop on Instagram security responses.

The YTDLnis reference is linked in cookie-recovery.md. This is an independent Android API implementation, not a copy of the YTDLnis application or its yt-dlp download engine. Database values and Netscape text never enter logs, reports or exported files. Manual and browser snapshots remain encrypted with the existing AES-GCM format.

Automated tests use controlled responses. Live Instagram account/device behavior must be reported separately; no server-side session expiry or revocation can be prevented.
