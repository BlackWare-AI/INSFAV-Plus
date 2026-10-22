function (expected, expectedId, preferHigh) {
    "use strict";
    const result = {page: location.href, username: expected, pageReady: document.readyState === "complete", candidateFound: false, searchCompleted: false};
    const wall = (code, reason = "UNKNOWN_RESPONSE") => JSON.stringify(Object.assign(result, {wall: code, reason}));
    const path = location.pathname.toLowerCase();
    if (/\/(challenge|checkpoint|captcha)(\/|$)/.test(path)) return wall("CHALLENGE_REQUIRED", "COOKIE_OR_LOGIN_GATE");
    if (path.startsWith("/accounts/login")) return wall("COOKIE_INVALID_EXPIRED", "COOKIE_OR_LOGIN_GATE");
    const visible = e => !!e && (e.getClientRects().length > 0);
    for (const f of document.querySelectorAll("form")) {
        if (!visible(f)) continue;
        const action = (f.getAttribute("action") || "").toLowerCase();
        if (action.includes("/challenge") || action.includes("/checkpoint")) return wall("CHALLENGE_REQUIRED", "COOKIE_OR_LOGIN_GATE");
        if (f.id === "loginForm" || action.includes("/accounts/login")) return wall("COOKIE_INVALID_EXPIRED", "COOKIE_OR_LOGIN_GATE");
    }
    const pageText = document.body && typeof document.body.innerText === "string" ? document.body.innerText.slice(0, 24000).toLowerCase().replace(/[’‘]/g, "'") : "";
    const unavailableText = /sorry, this page isn't available|bu sayfa kullanılamıyor|bu sayfaya ulaşılamıyor|sayfa kaldırılmış|bağlantı bozuk olabilir/.test(pageText);
    // Preserve the existing rate-limit barrier even when unavailable text also appears.
    if (/please wait a few minutes before you try again|we restrict certain activity/.test(pageText)) return wall("RATE_LIMITED", "TEXT_MATCH");
    const structured = new Set();
    let viewer = false, anonymous = false, routedError = false, structuredIdentity = false, visits = 0;
    const normalize = x => typeof x === "string" ? x.trim().replace(/^@/, "").toLowerCase() : "";
    const picture = x => x && (x.profile_pic_url_hd || (x.hd_profile_pic_url_info || {}).url ||
        x.profile_pic_url || (x.profile_pic_url_info || {}).url);
    function walk(o, depth) {
        if (!o || typeof o !== "object" || depth > 32 || ++visits > 30000) return;
        if (o.rootView && o.rootView.resource && o.rootView.resource.__dr === "PolarisErrorRoot.react") routedError = true;
        if (o.challenge_required === true || o.checkpoint_required === true) result.wall = "CHALLENGE_REQUIRED";
        if (o.login_required === true) result.wall = "COOKIE_INVALID_EXPIRED";
        const id = String(o.viewerId || o.viewer_id || "");
        if (expectedId && id === expectedId && o.is_logged_in !== false && o.is_authenticated !== false) viewer = true;
        for (const key of ["viewer", "current_user"]) {
            const u = o[key];
            if (u && normalize(u.username) && /^[0-9]+$/.test(String(u.id || u.pk || "")) && expectedId && String(u.id || u.pk) === expectedId) viewer = true;
        }
        if (expected && normalize(o.username) === expected && /^[0-9]+$/.test(String(o.id || o.pk || ""))) {
            structuredIdentity = true;
            if (o.has_anonymous_profile_picture === true || o.has_profile_pic === false) anonymous = true;
            else if ((o.has_anonymous_profile_picture === undefined || o.has_anonymous_profile_picture === false) && typeof picture(o) === "string") structured.add(picture(o));
        }
        for (const k of Object.keys(o)) walk(o[k], depth + 1);
    }
    let size = 0;
    for (const s of Array.from(document.scripts).slice(0, 256)) {
        let text = s.textContent.trim();
        if ((size += text.length) > 4194304) break;
        if (text.startsWith("window._sharedData = ")) text = text.slice(21).trim().replace(/;$/, "");
        if (!text.startsWith("{") && !text.startsWith("[")) continue;
        try { walk(JSON.parse(text), 0); } catch (_) {}
    }
    if (result.wall) return wall(result.wall, "COOKIE_OR_LOGIN_GATE");
    result.authenticated = viewer;
    if (!expected) return JSON.stringify(result);
    result.unavailableText = unavailableText;
    result.routedError = routedError;
    const safe = raw => {
        try {
            const u = new URL(raw);
            return u.protocol === "https:" && !u.username && !u.password && (!u.port || u.port === "443") &&
                /\.(cdninstagram\.com|fbcdn\.net)$/.test(u.hostname) &&
                !/anonymous|default|logo|44884218_345707102882519_2446069589734326272/i.test(raw);
        } catch (_) { return false; }
    };
    const images = Array.from(document.images).slice(0, 400);
    const candidates = new Map();
    for (const img of images) {
        if (img.closest("article,nav,aside")) continue;
        const alt = (img.getAttribute("alt") || "").trim().toLowerCase();
        const bound = alt.startsWith(expected) && !/[a-z0-9_.]/.test(alt.charAt(expected.length)) && /profile picture|profile photo|profil/.test(alt);
        if (bound && img.closest("header,main") && safe(img.currentSrc || img.src)) candidates.set(img.currentSrc || img.src, img);
    }
    // Explicit text is the requested fast-skip policy; a current image candidate vetoes it.
    result.candidateFound = candidates.size > 0 || Array.from(structured).some(safe);
    if (unavailableText && !result.candidateFound) {
        result.pageReady = true;
        result.searchCompleted = true;
        return wall("PROFILE_PAGE_UNAVAILABLE", "TEXT_MATCH");
    }
    const canonical = document.querySelector('link[rel="canonical"]');
    let canonicalUser = "";
    try {
        const u = new URL(canonical ? canonical.href : location.href);
        if (u.protocol === "https:" && /^(www\.)?instagram\.com$/.test(u.hostname)) canonicalUser = normalize(u.pathname.replace(/^\/|\/$/g, ""));
    } catch (_) {}
    if (canonicalUser !== expected) return JSON.stringify(Object.assign(result, {reason: "CANONICAL_MISMATCH"}));
    result.identityConfirmed = canonicalUser === expected || structuredIdentity;
    result.searchCompleted = true;
    result.candidateFound = candidates.size > 0 || structured.size > 0;
    result.reason = result.pageReady ? "NO_CANDIDATE" : "PAGE_NOT_READY";
    if (!result.identityConfirmed && result.candidateFound) return JSON.stringify(Object.assign(result, {reason: "UNKNOWN_RESPONSE"}));
    let url = "", kind = "", img;
    if (preferHigh && structured.size === 1) {
        url = Array.from(structured)[0]; kind = "profile_json";
    } else if (candidates.size === 1) {
        [url, img] = candidates.entries().next().value;
        kind = "profile_header";
    } else if (candidates.size > 1) return wall("PROFILE_DATA_AMBIGUOUS");
    else if (structured.size === 1) {
        url = Array.from(structured)[0]; kind = "profile_json";
        if (!safe(url)) return wall("UNTRUSTED_SOURCE");
        img = images.find(i => (i.currentSrc || i.src) === url);
        if (!img) {
            if (!window.__insfavImage || window.__insfavImage.src !== url) {
                window.__insfavImage = new Image();
                window.__insfavImage.src = url;
            }
            img = window.__insfavImage;
        }
    }
    if (preferHigh && kind === "profile_header" && img) {
        let best = img.naturalWidth || 0;
        for (const item of (img.getAttribute("srcset") || "").split(",")) {
            const parts = item.trim().split(/\s+/);
            if (parts.length === 2 && /^\d+w$/.test(parts[1]) && safe(parts[0]) && parseInt(parts[1], 10) > best) {
                best = parseInt(parts[1], 10); url = parts[0];
            }
        }
    }
    if (url && (!img || (img.currentSrc || img.src) !== url)) {
        if (!safe(url)) return wall("UNTRUSTED_SOURCE");
        if (!window.__insfavImage || window.__insfavImage.src !== url) {
            window.__insfavImage = new Image(); window.__insfavImage.src = url;
        }
        img = window.__insfavImage;
    }
    // Search identity-bound images before treating visible text or routed UI as evidence.
    // Repeated snapshots of this document do not constitute independent confirmation.
    if (!url) {
        if (routedError) return wall("PROFILE_PAGE_UNAVAILABLE", "ROUTED_ERROR");
        if (anonymous && structuredIdentity && result.pageReady) return wall("PROFILE_DEFAULT_AVATAR", "NO_CANDIDATE");
        return JSON.stringify(result);
    }
    if (!img || !img.complete || img.naturalWidth < 32 || img.naturalHeight < 32)
        return JSON.stringify(Object.assign(result, {reason: "PAGE_NOT_READY"}));
    return JSON.stringify(Object.assign(result, {url, kind, loaded: true, width: img.naturalWidth, height: img.naturalHeight}));
}
