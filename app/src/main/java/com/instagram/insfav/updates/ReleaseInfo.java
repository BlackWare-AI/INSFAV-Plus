package com.instagram.insfav.updates;

import org.json.*;
import java.io.IOException;
import java.net.URI;

/** Only stable releases and APK assets from this application's own repository are accepted. */
public final class ReleaseInfo {
    public final String tag,title,notes,url,asset,digest;public final long size,id;
    private ReleaseInfo(JSONObject release,JSONObject apk)throws Exception{
        id=release.getLong("id");tag=release.getString("tag_name");title=release.optString("name",tag);notes=release.optString("body","");url=release.getString("html_url");asset=apk.getString("browser_download_url");size=apk.getLong("size");digest=apk.optString("digest","");
    }
    public static String repo(String packageName){if(packageName.equals("com.instagram.insfav.plus"))return "BlackWare-AI/INSFAV-Plus";if(packageName.equals("com.instagram.insfav"))return "BlackWare-AI/INSFAV";throw new IllegalArgumentException("Unknown application");}
    public static ReleaseInfo parse(String packageName,JSONObject release)throws Exception{
        String root="https://github.com/"+repo(packageName)+"/releases/";
        if(release.optBoolean("draft")||release.optBoolean("prerelease")||!release.getString("html_url").startsWith(root+"tag/"))throw new IOException("No stable release");
        JSONArray assets=release.getJSONArray("assets");JSONObject chosen=null;
        for(int i=0;i<assets.length();i++){JSONObject a=assets.getJSONObject(i);String name=a.optString("name").toLowerCase(java.util.Locale.ROOT);if(!name.endsWith(".apk")||name.contains("debug")||name.contains("unsigned"))continue;
            URI uri=URI.create(a.getString("browser_download_url"));if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||!"github.com".equals(uri.getHost())||!uri.getPath().startsWith("/"+repo(packageName)+"/releases/download/"))throw new IOException("Unexpected APK source");
            if(chosen!=null)throw new IOException("Multiple APKs; use the release page");chosen=a;
        }
        if(chosen==null||chosen.getLong("size")<1||chosen.getLong("size")>256L*1024*1024)throw new IOException("No supported APK");
        String hash=chosen.optString("digest","");if(!hash.isEmpty()&&!hash.matches("sha256:[0-9a-fA-F]{64}"))throw new IOException("Invalid asset digest");
        return new ReleaseInfo(release,chosen);
    }
    public JSONObject json()throws JSONException{return new JSONObject().put("id",id).put("tag",tag).put("title",title).put("notes",notes).put("url",url).put("asset",asset).put("size",size).put("digest",digest);}
    public static boolean trustedDownload(URI uri){String h=uri.getHost();return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&("github.com".equals(h)||"release-assets.githubusercontent.com".equals(h)||"objects.githubusercontent.com".equals(h));}
    private static int[] version(String v){java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:^|v)([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-build[.-]([0-9]+))?").matcher(v);if(!m.find())return null;try{return new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),m.group(4)==null?0:Integer.parseInt(m.group(4))};}catch(NumberFormatException e){return null;}}
    public static boolean newer(String tag,String installed,long code){int[] a=version(tag),b=version(installed);if(a==null||b==null)return false;for(int i=0;i<3;i++){if(a[i]!=b[i])return a[i]>b[i];}return a[3]>code;}
}
