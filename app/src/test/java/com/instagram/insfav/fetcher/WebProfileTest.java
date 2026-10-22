package com.instagram.insfav.fetcher;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE)
public class WebProfileTest {
    JSONObject valid() throws Exception {
        return new JSONObject().put("page","https://www.instagram.com/example/").put("username","example")
            .put("url",ProviderTest.IMAGE).put("kind","profile_header").put("loaded",true).put("width",128).put("height",128);
    }
    @Test public void acceptsOnlyIdentityBoundLoadedProfileImage() throws Exception {
        assertEquals(ProviderTest.IMAGE,WebProfilePolicy.image(valid(),"example").toString());
        for(String field:new String[]{"username","page","url","kind","loaded","width"}) {
            JSONObject data=valid();
            if(field.equals("loaded"))data.put(field,false);
            else if(field.equals("width"))data.put(field,0);
            else data.put(field,"https://evil.test/post");
            try {WebProfilePolicy.image(data,"example");fail(field);}catch(ProfileProvider.Failure expected) {}
        }
    }
    @Test public void resourcesAreHttpsAndExplicitHostsOnly() {
        assertTrue(WebProfilePolicy.resource("https://static.cdninstagram.com/script.js"));
        for(String raw:new String[]{"http://www.instagram.com/","https://www.instagram.com.evil.test/","https://evil.instagram.com/","file:///sdcard/a","https://user@www.instagram.com/","https://www.instagram.com:444/"})
            assertFalse(raw,WebProfilePolicy.resource(raw));
    }
    @Test public void redirectsCannotOpenExternalPagesOrOtherAccounts() {
        assertEquals("",WebProfilePolicy.navigation("https://www.instagram.com/example/","example"));
        assertEquals("COOKIE_INVALID_EXPIRED",WebProfilePolicy.navigation("https://www.instagram.com/accounts/login/","example"));
        assertEquals("CHALLENGE_REQUIRED",WebProfilePolicy.navigation("https://www.instagram.com/challenge/","example"));
        assertEquals("WEBVIEW_NAVIGATION_BLOCKED",WebProfilePolicy.navigation("https://www.instagram.com/other/","example"));
        assertEquals("WEBVIEW_NAVIGATION_BLOCKED",WebProfilePolicy.navigation("https://evil.test/example/","example"));
    }
    @Test public void ajaxRateLimitAndSessionFailuresPauseButImage404IsNotProfileDeletion() {
        ProfileProvider.Failure rate=WebProfilePolicy.http(429,false,"120");
        assertEquals("RATE_LIMITED",rate.code);assertTrue(rate.pause);assertTrue(rate.retryAt>=System.currentTimeMillis()+119000);
        assertEquals("ACCESS_BLOCKED",WebProfilePolicy.http(401,false,null).code);
        assertFalse(SessionState.invalid(WebProfilePolicy.http(401,false,null)));
        assertEquals("COOKIE_INVALID_EXPIRED",WebProfilePolicy.http(401,true,null).code);
        assertNull(WebProfilePolicy.http(404,false,null));
        assertEquals("PROFILE_NOT_FOUND_OR_UNAVAILABLE",WebProfilePolicy.http(404,true,null).code);
        assertFalse(WebProfilePolicy.http(404,true,null).pause);
    }
    @Test public void snapshotIsBoundedAndRequiresJsonStringCallback() throws Exception {
        assertEquals("example",WebProfilePolicy.snapshot(JSONObject.quote(valid().toString())).getString("username"));
        for(String raw:new String[]{"null","{}",JSONObject.quote("bad"),new String(new char[33000])}) {
            try {WebProfilePolicy.snapshot(raw);fail();}catch(ProfileProvider.Failure error){assertEquals("WEBVIEW_SNAPSHOT_INVALID",error.code);}
        }
    }
}
