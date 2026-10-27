package com.instagram.insfav.fetcher;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.webkit.CookieManager;
import java.io.File;
import java.util.concurrent.*;

/** YTDLnis-style own WebView database -> Netscape -> existing saved-cookie parser.
 * Independently implemented with Android APIs; never exports plaintext to a file.
 */
final class NetscapeBrowserCookies {
    static CookieVault vault(Context c) { return CookieVault.namedBrowser(c, "instagram-netscape-cookies.enc"); }
    static CookieJar read(Context c) throws ProfileProvider.Failure {
        try {
            FutureTask<Void> flush = new FutureTask<>(() -> { CookieManager.getInstance().flush(); return null; });
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())flush.run();else main.post(flush);
            flush.get(10,TimeUnit.SECONDS);
            File base=new File(c.getApplicationInfo().dataDir,"app_webview/Default");
            File file=new File(base,"Cookies");if(!file.isFile())file=new File(base,"Network/Cookies");
            if(!file.isFile())throw new ProfileProvider.Failure("COOKIE_DATABASE_UNAVAILABLE");
            StringBuilder text=new StringBuilder("# Netscape HTTP Cookie File\n");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY);
                Cursor rows=db.query("cookies",new String[]{"host_key","path","name","value","is_secure","expires_utc"},
                    "host_key IN (?,?,?,?)",new String[]{"instagram.com",".instagram.com","www.instagram.com",".www.instagram.com"},null,null,null)) {
                while(rows.moveToNext()) {
                    String host=rows.getString(0),path=rows.getString(1),name=rows.getString(2),value=rows.getString(3);
                    if(path==null||!path.matches("/[A-Za-z0-9_./%~\\-]*"))continue;
                    long chrome=rows.getLong(5),expires=chrome==0?0:chrome/1000000L-11644473600L;
                    if(expires<0||expires!=0&&expires<=System.currentTimeMillis()/1000)continue;
                    // OS-encrypted rows have no plaintext value. Use the supported API, never decrypt Chromium.
                    if(value==null||value.isEmpty()) {
                        String url="https://"+host.replaceFirst("^\\.","")+path;
                        FutureTask<String> api=new FutureTask<>(()->CookieManager.getInstance().getCookie(url));
                        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())api.run();else main.post(api);
                        String header=api.get(10,TimeUnit.SECONDS);value="";
                        if(header!=null)for(String part:header.split(";")){String item=part.trim();if(item.startsWith(name+"=")){value=item.substring(name.length()+1);break;}}
                    }
                    if(value.isEmpty())continue;
                    if(value.indexOf('\t')>=0||value.indexOf('\n')>=0||value.indexOf('\r')>=0)throw new ProfileProvider.Failure("COOKIE_INVALID");
                    text.append(host).append('\t').append(host.startsWith(".")?"TRUE":"FALSE").append('\t').append(path).append('\t')
                        .append(rows.getInt(4)==1?"TRUE":"FALSE").append('\t').append(expires).append('\t').append(name).append('\t').append(value).append('\n');
                    if(text.length()>CookieJar.MAX_INPUT)throw new ProfileProvider.Failure("COOKIE_INVALID");
                }
            }
            return CookieJar.parse(text.toString(),System.currentTimeMillis());
        }catch(ProfileProvider.Failure e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ProfileProvider.Failure("REQUEST_CANCELLED");}
        catch(Exception e){throw new ProfileProvider.Failure("COOKIE_DATABASE_UNAVAILABLE");}
    }
    static CookieJar capture(Context c) throws ProfileProvider.Failure {
        long epoch=SessionState.prefs(c).getLong("browserCookieEpoch",0);
        CookieJar jar=read(c);
        synchronized(BrowserCookies.LOCK) {
            if(epoch!=SessionState.prefs(c).getLong("browserCookieEpoch",0))throw new ProfileProvider.Failure("REQUEST_CANCELLED");
            try{vault(c).save(jar);}catch(Exception e){throw new ProfileProvider.Failure("COOKIE_STORAGE_FAILED");}
        }
        return jar;
    }
    static CookieJar forDownload(Context c)throws ProfileProvider.Failure {
        try{return capture(c);}catch(ProfileProvider.Failure e){
            if(e.code.equals("REQUEST_CANCELLED"))throw e;
            if(e.code.equals("COOKIE_STORAGE_FAILED"))return read(c);
            if(!vault(c).configured())throw e;
            return vault(c).load();
        }
    }
    private NetscapeBrowserCookies() { }
}
