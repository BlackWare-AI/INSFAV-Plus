# Instagram cookie / HD regression comparison

Compared INSFAV Plus `plus-v1.1.4-build.6` with the session builds. The older manual-cookie provider did not make an explicit HD profile request depend on an additional hidden authenticated home page. New session builds introduced that gate and required an isolated WebView profile for manual-cookie injection. Both could prevent the actual image request before profile resolution.

The recovery keeps the browser session but passes its captured Cookie header directly to the HTTP image-resolution path. The profile response is identity-bound; the HD URL wins over a small avatar URL. A public web-client app ID is used only on first-party web API requests, with the normal WebView user agent. It does not emulate Instagram's Android application or device identity. CDN requests carry no Instagram cookies. Security walls stop requests; unsupported JSON endpoints fall back to the same account's HTML and existing WebView resolver.

YTDLnis reference inspected at `7c8e0483c13b678a159e84e73792c2d6a9398beb`:
- [CookieViewModel.getCookiesFromDB](https://github.com/deniscerri/ytdlnis/blob/7c8e0483c13b678a159e84e73792c2d6a9398beb/app/src/main/java/com/deniscerri/ytdl/database/viewmodel/CookieViewModel.kt#L99): flushes its WebView, reads Chromium SQLite cookie metadata and generates Netscape records.
- [WebViewActivity](https://github.com/deniscerri/ytdlnis/blob/7c8e0483c13b678a159e84e73792c2d6a9398beb/app/src/main/java/com/deniscerri/ytdl/ui/more/cookies/WebViewActivity.kt): persistent non-incognito browser and in-app generation action.

This implementation independently uses Android APIs, restricts reads to this application's default Instagram store, prefers CookieManager values over database values, and encrypts its snapshot using the existing vault format. No YTDLnis source code was copied. It does not copy cookie logging, blanket third-party cookies, geolocation permissions or incognito data clearing.

The additional cache-file image staging was also compared with build 6: production already uses the application cache directory. No evidence established it as the cause, so it is unchanged.
