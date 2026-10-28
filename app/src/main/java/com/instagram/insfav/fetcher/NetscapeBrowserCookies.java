package com.instagram.insfav.fetcher;

import android.content.Context;

/** Own WebView -> Netscape rows -> saved-cookie parser. No external app access or plaintext file. */
final class NetscapeBrowserCookies {
    static CookieVault vault(Context c) { return CookieVault.namedBrowser(c, "instagram-netscape-cookies.enc"); }
    static CookieJar read(Context c) throws ProfileProvider.Failure {
        return CookieJar.parse(text(BrowserCookies.read(c)), System.currentTimeMillis());
    }
    static String text(CookieJar jar) {
        StringBuilder out = new StringBuilder("# Netscape HTTP Cookie File\n# INSFAV WebView cookies\n");
        for (CookieJar.Entry e : jar.entries) {
            if (e.name.equals("sessionid")) out.append("#HttpOnly_");
            out.append(e.subdomains ? "." : "").append(e.domain).append('\t')
                .append(e.subdomains ? "TRUE" : "FALSE").append('\t').append(e.path).append("\tTRUE\t")
                .append(e.expires).append('\t').append(e.name).append('\t').append(e.value).append('\n');
        }
        return out.toString();
    }
    static CookieJar capture(Context c) throws ProfileProvider.Failure { return BrowserCookieStore.captureNow(c, "netscape"); }
    static CookieJar forDownload(Context c) throws ProfileProvider.Failure { return read(c); }
    private NetscapeBrowserCookies() { }
}
