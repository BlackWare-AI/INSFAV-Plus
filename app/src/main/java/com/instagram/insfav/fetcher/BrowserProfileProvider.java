package com.instagram.insfav.fetcher;

import android.content.Context;
import android.webkit.WebSettings;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Browser cookies are captured once per provider, then used directly by the HTTP download engine. */
final class BrowserProfileProvider implements ProfileProvider {
    interface Legacy { ProfileProvider open() throws Failure; }
    private final Context context;
    private final CookieJar jar;
    private final PublicProfileProvider.Connections network;
    private final Legacy legacy;
    private final String agent;
    private final Set<HttpsURLConnection> active=ConcurrentHashMap.newKeySet();
    private final Map<String,Set<HttpsURLConnection>> pages=new ConcurrentHashMap<>();
    private final Map<String,PublicProfileProvider> images=new ConcurrentHashMap<>();
    private volatile boolean cancelled;
    private volatile Failure barrier;
    private volatile ProfileProvider fallback;
    BrowserProfileProvider(Context c, CookieJar jar, boolean manual) throws Failure {
        this(c,jar,url->(HttpsURLConnection)url.openConnection(),()->new WebViewProfileProvider(c,manual?jar:null));
    }
    BrowserProfileProvider(Context c,CookieJar jar,PublicProfileProvider.Connections network,Legacy legacy) {
        context=c.getApplicationContext();this.jar=jar;this.network=network;this.legacy=legacy;
        agent=WebSettings.getDefaultUserAgent(c);
    }
    @Override public void authenticate() throws Failure {
        try {
            JSONObject json=new JSONObject(new String(request(new URL(InstagramSession.ORIGIN+"api/v1/accounts/current_user/?edit=true"),""),StandardCharsets.UTF_8));
            JSONObject user=json.optJSONObject("user");
            String expected=jar.value("ds_user_id",System.currentTimeMillis());
            if(!"ok".equals(json.optString("status"))||user==null||!user.optString("pk",user.optString("id")).matches("[1-9][0-9]*")||(!expected.isEmpty()&&!expected.equals(user.optString("pk",user.optString("id")))))throw new Failure("COOKIE_AUTH_UNVERIFIED");
            InstagramSession.recordVerified(context,InstagramSession.manual(context));
        }catch(JSONException|MalformedURLException e){throw new Failure("COOKIE_AUTH_UNVERIFIED");}
        catch(Failure e){if(FailurePolicy.pause(e)){barrier=e;throw e;}legacy().authenticate();}
    }
    private synchronized ProfileProvider legacy() throws Failure {if(fallback==null)fallback=legacy.open();return fallback;}
    @Override public byte[] fetch(String user) throws Failure {return fetch(user,false);}
    @Override public byte[] fetch(String user,boolean full) throws Failure {
        if(!Identity.valid(user)||!Identity.normalize(user).equals(user))throw new Failure("PROFILE_IDENTITY_UNVERIFIED");
        if(cancelled)throw new Failure("REQUEST_CANCELLED"); if(barrier!=null)throw barrier;
        URL picture;
        try { picture=resolveNative(user); }
        catch(Failure e){if(FailurePolicy.pause(e)){barrier=e;throw e;}return legacy().fetch(user,full);}
        PublicProfileProvider transfer=new PublicProfileProvider(network,null,new File(context.getCacheDir(),"profile-downloads"));
        transfer.userAgent=agent;images.put(user,transfer);
        try {if(cancelled)throw new Failure("REQUEST_CANCELLED");return transfer.download(picture);}
        finally {images.remove(user,transfer);}
    }
    private URL resolveNative(String user) throws Failure {
        try {
            JSONObject json=new JSONObject(new String(request(new URL(InstagramSession.ORIGIN+"api/v1/users/web_profile_info/?username="+URLEncoder.encode(user,"UTF-8")),user),StandardCharsets.UTF_8));
            return PublicProfileProvider.photoJson(json,user);
        }catch(JSONException|IOException ignored) { }
        catch(Failure e){if(FailurePolicy.pause(e))throw e;}
        try{return PublicProfileProvider.photo(new String(request(new URL(InstagramSession.ORIGIN+user+"/"),user),StandardCharsets.UTF_8),user);}
        catch(MalformedURLException e){throw new Failure("INVALID_IMAGE_URL");}
    }
    private byte[] request(URL url,String user) throws Failure {
        for(int redirect=0;redirect<4;redirect++) {
            if(cancelled)throw new Failure("REQUEST_CANCELLED");
            if(jar.value("sessionid",System.currentTimeMillis()).isEmpty())throw new Failure("COOKIE_EXPIRED",0,0,true);
            if(!PublicProfileProvider.trusted(url,false))throw new Failure("UNTRUSTED_SOURCE");
            HttpsURLConnection connection=null;
            try {
                connection=network.open(url);active.add(connection);pages.computeIfAbsent(user,key->ConcurrentHashMap.newKeySet()).add(connection);
                connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent",agent);
                connection.setRequestProperty("Cookie",jar.header(url,System.currentTimeMillis()));
                connection.setRequestProperty("Accept","application/json,text/html;q=0.9");
                connection.setRequestProperty("Accept-Encoding","identity");
                connection.setRequestProperty("Referer",user.isEmpty()?InstagramSession.ORIGIN:InstagramSession.ORIGIN+user+"/");
                connection.setRequestProperty("Origin","https://www.instagram.com");
                // Instagram's public web-client identifier, not an Android app/device identity.
                if(url.getPath().startsWith("/api/")){connection.setRequestProperty("X-IG-App-ID","936619743392459");connection.setRequestProperty("X-IG-WWW-Claim","0");}
                String csrf=jar.value("csrftoken",System.currentTimeMillis());if(!csrf.isEmpty())connection.setRequestProperty("X-CSRFToken",csrf);
                if(cancelled)throw new Failure("REQUEST_CANCELLED");
                int status=connection.getResponseCode();
                if(status==429)throw new Failure("RATE_LIMITED",status,PublicProfileProvider.retryAt(connection.getHeaderField("Retry-After"),System.currentTimeMillis()),true);
                if(status==401)throw new Failure("COOKIE_INVALID_EXPIRED",status,0,true);
                if(status==403) {
                    try(InputStream error=connection.getErrorStream()) {
                        if(error!=null) {byte[] data=new byte[65536];int count=error.read(data);if(count>0)walls(new String(data,0,count,StandardCharsets.UTF_8));}
                    }
                    if(url.getPath().startsWith("/api/"))throw new Failure("PROFILE_API_UNAVAILABLE");
                    throw new Failure("ACCESS_BLOCKED",status,0,true);
                }
                if(status>=300&&status<400){String target=connection.getHeaderField("Location");if(target==null)throw new Failure("REDIRECT_INVALID");URL next=new URL(url,target);String path=next.getPath();if(path.contains("challenge")||path.contains("checkpoint"))throw new Failure("CHALLENGE_REQUIRED",status,0,true);if(path.startsWith("/accounts/login"))throw new Failure("COOKIE_INVALID_EXPIRED",status,0,true);url=next;continue;}
                if(status!=200)throw new Failure("HTTP_ERROR",status,0,false);
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192];int n;
                    while((n=input.read(buffer))!=-1){if(cancelled)throw new Failure("REQUEST_CANCELLED");if(out.size()+n>4*1024*1024)throw new Failure("RESPONSE_TOO_LARGE");out.write(buffer,0,n);}
                    byte[] data=out.toByteArray();String body=new String(data,StandardCharsets.UTF_8);
                    walls(body);
                    return data;
                }
            }catch(IOException e){throw new Failure(cancelled?"REQUEST_CANCELLED":"NETWORK_ERROR");}
            finally {if(connection!=null){active.remove(connection);Set<HttpsURLConnection> requests=pages.get(user);if(requests!=null)requests.remove(connection);connection.disconnect();}}
        }
        throw new Failure("REDIRECT_LIMIT");
    }
    private static void walls(String body) throws Failure {
        PublicProfileProvider.rejectWall(body,true);
        String visible=body.replaceAll("(?s)<script\\b.*?</script>","").toLowerCase(Locale.ROOT);
        if(!body.trim().startsWith("{")&&(visible.contains("please wait a few minutes before you try again")||visible.contains("we restrict certain activity")))
            throw new Failure("RATE_LIMITED",0,System.currentTimeMillis()+60000,true);
        if(body.trim().startsWith("{"))try {
            JSONObject json=new JSONObject(body);
            String message=json.optString("message").toLowerCase(Locale.ROOT);
            if(message.contains("please wait a few minutes before you try again")||message.contains("we restrict certain activity"))throw new Failure("RATE_LIMITED",0,System.currentTimeMillis()+60000,true);
            if(message.equals("feedback_required"))throw new Failure("ACCESS_BLOCKED",0,0,true);
            if(message.equals("checkpoint_required")||!json.optString("checkpoint_url").isEmpty())throw new Failure("CHALLENGE_REQUIRED",0,0,true);
            if(json.optBoolean("challenge_required")||json.optBoolean("checkpoint_required")||json.optJSONObject("challenge")!=null||json.optString("message").contains("challenge_required"))throw new Failure("CHALLENGE_REQUIRED",0,0,true);
            if(json.optBoolean("login_required")||"login_required".equals(json.optString("message")))throw new Failure("COOKIE_INVALID_EXPIRED",0,0,true);
        }catch(JSONException ignored) { }
    }
    @Override public void cancel(){cancelled=true;for(HttpsURLConnection c:active)c.disconnect();for(PublicProfileProvider p:images.values())p.cancel();if(fallback!=null)fallback.cancel();}
    @Override public void cancel(String user){Set<HttpsURLConnection> requests=pages.get(user);if(requests!=null)for(HttpsURLConnection connection:requests)connection.disconnect();PublicProfileProvider p=images.get(user);if(p!=null)p.cancel();if(fallback!=null)fallback.cancel(user);}
    @Override public void trim(int limit){if(fallback!=null)fallback.trim(limit);}
    @Override public Failure blockingFailure(){return barrier!=null?barrier:fallback==null?null:fallback.blockingFailure();}
    @Override public void close(){cancel();if(fallback!=null)fallback.close();}
}
