package com.instagram.insfav;

import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.WebView;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Real foreground clipboard -> bridge -> editor -> encrypted Save; only synthetic values. */
final class CookiePasteDeviceCheck {
    static void run(Instrumentation host, Context c, WebView web) throws Exception {
        String fixture="# Netscape HTTP Cookie File\n.instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tOFFLINE_PASTE_ONLY%3AFIXTURE\n";
        File vault=new File(c.getFilesDir(),"fetcher-cookies.enc");
        byte[] previous=vault.exists()?Files.readAllBytes(vault.toPath()):null;
        try {
            host.runOnMainSync(()->{
                c.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Offline cookie fixture",fixture));
                web.evaluateJavascript("openSettings();openFetcher();document.getElementById('cookie-text').value='';request('fetcher-paste')",null);
            });
            await(host,web,"document.getElementById('cookie-text').value==="+JSONObject.quote(fixture));
            host.runOnMainSync(()->web.evaluateJavascript("document.getElementById('cookie-save').click()",null));
            await(host,web,"document.getElementById('cookie-text').value===''&&!document.getElementById('cookie-save').disabled");
            if(!vault.exists())throw new AssertionError("Manual cookie Save did not create private storage");
            if(new String(Files.readAllBytes(vault.toPath()),java.nio.charset.StandardCharsets.ISO_8859_1).contains("OFFLINE_PASTE_ONLY"))throw new AssertionError("Manual cookies were not encrypted");
            android.content.SharedPreferences prefs=c.getSharedPreferences("thumbnail-fetcher",Context.MODE_PRIVATE);
            if(prefs.getBoolean("armed",false)||!prefs.getBoolean("userPaused",false)||com.instagram.insfav.fetcher.Fetcher.running())throw new AssertionError("Paste/Save started bulk retrieval");
            host.runOnMainSync(()->web.evaluateJavascript("document.getElementById('fetcher-disabled').checked=true;document.getElementById('fetcher-disabled').onchange()",null));
            await(host,web,"fetcherState.bulkDisabled===true&&!fetcherState.running&&document.getElementById('fetcher-missing').disabled");
            if(!prefs.getBoolean("bulkDisabled",false)||prefs.getBoolean("armed",true))throw new AssertionError("Bulk disable was not persisted");
            if(c.getSystemService(android.app.NotificationManager.class).getActiveNotifications().length!=0)throw new AssertionError("Disabled bulk retained a notification");
            host.runOnMainSync(()->web.evaluateJavascript("document.getElementById('fetcher-disabled').checked=false;document.getElementById('fetcher-disabled').onchange()",null));
            await(host,web,"fetcherState.bulkDisabled===false&&!fetcherState.running");
            if(prefs.getBoolean("armed",true)||!prefs.getBoolean("userPaused",false))throw new AssertionError("Enable automatically resumed bulk retrieval");

        } finally {
            host.runOnMainSync(()->web.evaluateJavascript("closeFetcher();closeSettings()",null));
            if(previous==null)Files.deleteIfExists(vault.toPath());else Files.write(vault.toPath(),previous);
        }
    }
    private static void await(Instrumentation host,WebView web,String expression)throws Exception {
        AtomicBoolean done=new AtomicBoolean();long deadline=SystemClock.elapsedRealtime()+20000;
        while(!done.get()&&SystemClock.elapsedRealtime()<deadline){host.runOnMainSync(()->web.evaluateJavascript(expression,value->done.set("true".equals(value))));Thread.sleep(100);}
        if(!done.get())throw new AssertionError("Foreground cookie paste/save fixture timed out");
    }
}
