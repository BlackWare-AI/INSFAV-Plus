function (expected, expectedId) {
    "use strict";
    const result = {page: location.href, username: expected, pageReady: document.readyState === "complete", candidateFound: false, searchCompleted: false};
    const wall = (code, reason = "UNKNOWN_RESPONSE") => JSON.stringify(Object.assign(result, {wall: code, reason}));
    const path = location.pathname.toLowerCase();
    if (/\/(challenge|checkpoint|captcha)(\/|$)/.test(path)) return wall("CHALLENGE_REQUIRED");
    if (path.startsWith("/accounts/login")) return wall("COOKIE_INVALID_EXPIRED");
    const visible = e => !!e && (e.getClientRects().length > 0);
    for (const f of document.querySelectorAll("form")) {
        if (!visible(f)) continue;
        const action = (f.getAttribute("action") || "").toLowerCase();
        if (action.includes("/challenge") || action.includes("/checkpoint")) return wall("CHALLENGE_REQUIRED");
        if (f.id === "loginForm" || action.includes("/accounts/login")) return wall("COOKIE_INVALID_EXPIRED");
    }
    const pageText = document.body && typeof document.body.innerText === "string" ? document.body.innerText.slice(0, 24000).toLowerCase().replace(/[’‘]/g, "'") : "";
    const unavailableText = /sorry, this page isn't available|bu sayfa kullanılamıyor|bu sayfaya ulaşılamıyor|sayfa kaldırılmış|bağlantı bozuk olabilir/.test(pageText);
    if (/please wait a few minutes before you try again|we restrict certain activity/.test(pageText)) return wall("RATE_LIMITED", "TEXT_MATCH");
    const structured = new Set();
    let viewer = false, anonymous = false, routedError = false, visits = 0;
    const normalize = x => typeof x === "string" ? x.trim().replace(/^@/, "").toLowerCase() : "";
    const picture = x => x && (x.profile_pic_url_hd || (x.hd_profile_pic_url_info || {}).url ||
        x.profile_pic_url || (x.profile_pic_url_info || {}).url);
    function walk(o, depth) {
        if (!o || typeof o !== "object" || depth > 32 || ++visits > 30000) return;
        if (o.rootView && o.rootView.resource && o.rootView.resource.__dr === "PolarisErrorRoot.react") routedError = true;
        if (o.challenge_required === true || o.checkpoint_required === true) result.wall = "CHALLENGE_REQUIRED";
        if (o.login_required === true) result.wall = "COOKIE_INVALID_EXPIRED";
        const id = String(o.viewerId || o.viewer_id || "");
        if (/^[1-9][0-9]*$/.test(expectedId) && id === expectedId && o.is_logged_in !== false && o.is_authenticated !== false) viewer = true;
        for (const key of ["viewer", "current_user"]) {
            const u = o[key];
            if (u && normalize(u.username) && /^[0-9]+$/.test(String(u.id || u.pk || "")) && /^[1-9][0-9]*$/.test(expectedId) && String(u.id || u.pk) === expectedId) viewer = true;
        }
        if (expected && normalize(o.username) === expected && /^[0-9]+$/.test(String(o.id || o.pk || ""))) {
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
    if (result.wall) return JSON.stringify(result);
    // A signed-in inbox plus the account avatar in navigation is page evidence,
    // unlike feed images or cookie presence. Keep checks on this HTTPS origin.
    if (!expected && /^[1-9][0-9]*$/.test(expectedId)) {
        const localPath = link => {
            try { const u = new URL(link.href, location.href); return u.origin === location.origin && !u.username && !u.password ? u.pathname : ""; }
            catch (_) { return ""; }
        };
        const inbox = Array.from(document.querySelectorAll('a[href]')).some(link => visible(link) && /^\/direct\/inbox\/?$/.test(localPath(link)));
        const ownAvatar = Array.from(document.querySelectorAll('nav a[href], [role="navigation"] a[href], footer a[href]')).some(link => {
            const match = /^\/([a-z0-9_.]{1,30})\/?$/i.exec(localPath(link));
            return visible(link) && match && !["accounts", "direct", "explore", "reels", "stories", "p"].includes(match[1].toLowerCase()) && visible(link.querySelector('img'));
        });
        if (inbox && ownAvatar) viewer = true;
    }
    // Fall back to a first-party, read-only current-user response.
    // No session-cookie credentials cross the JavaScript bridge; a cookie alone is never proof.
    if (!expected && !viewer) {
        let proof = window.__insfavSessionCheck;
        if (!proof || proof.account !== expectedId) {
            proof = {account: expectedId, authenticated: false, complete: false, authoritative: false, wall: ""};
            window.__insfavSessionCheck = proof;
            const controller = new AbortController();
            const timeout = setTimeout(() => controller.abort(), 8000);
            fetch("/api/v1/accounts/current_user/?edit=true", {credentials: "same-origin", cache: "no-store", redirect: "error", signal: controller.signal})
                .then(async response => {
                    if (response.status === 403) { proof.authoritative = true; proof.wall = "ACCESS_BLOCKED"; }
                    if (response.status === 429) { proof.wall = "RATE_LIMITED"; return; }
                    if (response.status === 401) { proof.wall = "COOKIE_INVALID_EXPIRED"; return; }
                    if (!(response.headers.get("content-type") || "").includes("application/json")) return;
                    const data = await response.json();
                    if (data.challenge_required || data.checkpoint_required || data.challenge) { proof.wall = "CHALLENGE_REQUIRED"; return; }
                    if (data.login_required || data.message === "login_required") { proof.wall = "COOKIE_INVALID_EXPIRED"; return; }
                    const user = data.user;
                    if (data.status === "ok" || data.status === "fail") proof.authoritative = true;
                    proof.authenticated = response.ok && data.status === "ok" && !!user && typeof user.username === "string" && !!user.username && /^[1-9][0-9]*$/.test(String(user.pk || user.id || "")) && (!expectedId || String(user.pk || user.id) === expectedId);
                }).catch(() => {}).finally(() => { proof.complete = true; clearTimeout(timeout); });
        }
        if (proof.wall) return wall(proof.wall, "COOKIE_OR_LOGIN_GATE");
        if (!proof.complete) viewer = false;
        else if (proof.authoritative) viewer = proof.authenticated;
    }
    result.authenticated = viewer;
    if (!expected) return JSON.stringify(result);
    result.unavailableText = unavailableText;
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
    if (routedError && !result.candidateFound) return wall("PROFILE_PAGE_UNAVAILABLE", "ROUTED_ERROR");
    if (anonymous) return wall("PROFILE_DEFAULT_AVATAR");
    const canonical = document.querySelector('link[rel="canonical"]');
    let canonicalUser = "";
    try {
        const u = new URL(canonical ? canonical.href : location.href);
        if (u.protocol === "https:" && /^(www\.)?instagram\.com$/.test(u.hostname)) canonicalUser = normalize(u.pathname.replace(/^\/|\/$/g, ""));
    } catch (_) {}
    if (canonicalUser !== expected) return JSON.stringify(result);
    if(candidates.size>1)return wall("PROFILE_DATA_AMBIGUOUS");
    const pool=[];
    for(const [url,img] of candidates)pool.push({url,img,kind:"profile_header"});
    if(!window.__insfavHD)window.__insfavHD=new Map();
    for(const url of Array.from(structured).slice(0,8)){
        if(!safe(url))continue;
        let img=images.find(i=>(i.currentSrc||i.src)===url)||window.__insfavHD.get(url);
        if(!img){img=new Image();img.src=url;window.__insfavHD.set(url,img);}
        pool.push({url,img,kind:"profile_json"});
    }
    if(pool.some(c=>!c.img.complete))return JSON.stringify(result);
    const valid=pool.filter(c=>c.img.complete&&c.img.naturalWidth>=32&&c.img.naturalHeight>=32);
    valid.sort((a,b)=>b.img.naturalWidth*b.img.naturalHeight-a.img.naturalWidth*a.img.naturalHeight);
    if(!valid.length)return JSON.stringify(result);
    const {url,img,kind}=valid[0];
    return JSON.stringify(Object.assign(result,{url,kind,loaded:true,width:img.naturalWidth,height:img.naturalHeight}));
}
