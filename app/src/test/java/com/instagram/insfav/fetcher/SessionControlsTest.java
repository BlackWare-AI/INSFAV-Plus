package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class SessionControlsTest {
    Context c; android.content.SharedPreferences p;
    @Before public void setup() {
        c=RuntimeEnvironment.getApplication(); p=SessionState.prefs(c);p.edit().clear().commit();QueueControl.lock.set(false);
        Fetcher.initialize(c,new Fetcher.Host(){public Collection<String> users(){return Arrays.asList("example");}public String tree(){return "";}public String directory(){return "";}public long revision(){return 0;}public boolean hasImage(String user){return false;}public void imageWritten(String user,Uri uri,String tree){}});
    }
    @After public void cleanup(){QueueControl.lock.set(false);QueueControl.requested=false;Fetcher.closeViewer();}
    @Test public void disablePreservesQueueImagesCookiesAndRejectsBulkButAllowsHd() throws Exception {
        CookieManager manager=CookieManager.getInstance();manager.setCookie(InstagramSession.ORIGIN,"sessionid=OFFLINE_CONTROLS%3AFIXTURE; Secure; HttpOnly");
        File image=new File(c.getFilesDir(),"controls-sentinel.webp");Files.write(image.toPath(),new byte[]{1,2,3});
        try(QueueStore q=new QueueStore(c)){q.sync(Arrays.asList("example"));q.getWritableDatabase().execSQL("UPDATE accounts SET status='SUCCESS' WHERE username='example'");}
        Fetcher.setBulkDisabled(true);
        assertTrue(BulkControl.disabled(c));assertFalse(p.getBoolean("armed",true));assertTrue(p.getBoolean("userPaused",false));assertFalse(WorkScheduler.enabled(p));
        try{Fetcher.action("missing");fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("disabled"));}
        AtomicInteger calls=new AtomicInteger();FetchBroker broker=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String user){calls.incrementAndGet();return new byte[]{4};}public void cancel(){}},new TestTime());
        try{
            try{broker.submit("example",false).get(3,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){assertEquals("FETCHER_DISABLED",((ProfileProvider.Failure)expected.getCause()).code);}
            assertArrayEquals(new byte[]{4},broker.submit("example",true).get(3,TimeUnit.SECONDS));assertEquals(1,calls.get());
        }finally{broker.cancelAll();}
        assertTrue(manager.getCookie(InstagramSession.ORIGIN).contains("OFFLINE_CONTROLS%3AFIXTURE"));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(image.toPath()));
        try(QueueStore q=new QueueStore(c)){assertEquals(Integer.valueOf(1),q.counts().get("SUCCESS"));}
        Fetcher.initialize(c,new Fetcher.Host(){public Collection<String> users(){throw new AssertionError("Disabled fetcher must not scan source");}public String tree(){return "";}public String directory(){return "";}public long revision(){return 0;}public boolean hasImage(String user){return false;}public void imageWritten(String user,Uri uri,String tree){}});
        assertTrue(BulkControl.disabled(c));Fetcher.sync();
        Fetcher.setBulkDisabled(false);assertFalse(p.getBoolean("armed",true));assertTrue(p.getBoolean("userPaused",false));assertFalse(WorkScheduler.enabled(p));
    }
    @Test public void idleAndTerminalNotificationsAreRemoved() {
        NotificationManager notifications=c.getSystemService(NotificationManager.class);DownloadNotification.channel(c);
        Notification fixture=new Notification.Builder(c,"downloads").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Offline fixture").build();
        notifications.notify(3,fixture);assertEquals(1,notifications.getActiveNotifications().length);
        DownloadNotification.publish(c,3,"Paused");assertEquals(0,notifications.getActiveNotifications().length);
        QueueControl.lock.set(true);notifications.notify(1,fixture);DownloadNotification.publish(c,1,"Completed");assertEquals(0,notifications.getActiveNotifications().length);
        notifications.notify(3,fixture);DownloadNotification.finish(c);QueueControl.lock.set(false);DownloadNotification.finish(c);assertEquals(0,notifications.getActiveNotifications().length);
    }
    @Test public void visibleNativeCookiePanelReadsRealManagerIncludingHttpOnlyEncoding() throws Exception {
        CookieManager manager=CookieManager.getInstance();manager.setCookie(InstagramSession.ORIGIN,"sessionid=OFFLINE_PANEL%3AFIXTURE; Secure; HttpOnly");
        org.robolectric.android.controller.ActivityController<InstagramLoginActivity> controller=Robolectric.buildActivity(InstagramLoginActivity.class).create().start().resume();
        try{
            ReflectionHelpers.callInstanceMethod(controller.get(),"showCookies");
            ReflectionHelpers.setField(controller.get(),"savedTab",false);
            ReflectionHelpers.callInstanceMethod(controller.get(),"refreshCookies");
            TextView cookies=ReflectionHelpers.getField(controller.get(),"cookies");
            assertTrue(cookies.getText().toString().contains("OFFLINE_PANEL%3AFIXTURE"));assertFalse(cookies.isSaveEnabled());
            for(java.lang.reflect.Method method:InstagramSession.class.getDeclaredMethods())assertNotEquals("test",method.getName());
            WebView browser=ReflectionHelpers.getField(controller.get(),"browser");
            int reloads=Shadows.shadowOf(browser).getReloadInvocations();
            Button refresh=findRefresh((android.view.ViewGroup)controller.get().findViewById(android.R.id.content));assertNotNull(refresh);refresh.performClick();
            assertEquals(reloads+1,Shadows.shadowOf(browser).getReloadInvocations());
            assertTrue(manager.getCookie(InstagramSession.ORIGIN).contains("OFFLINE_PANEL%3AFIXTURE"));
            controller.pause();assertEquals("",cookies.getText().toString());
        }finally{controller.stop().destroy();}
    }
    private Button findRefresh(android.view.ViewGroup group) {
        for(int i=0;i<group.getChildCount();i++) {
            android.view.View view=group.getChildAt(i);
            if(view instanceof Button && "Refresh".contentEquals(((Button)view).getText()))return (Button)view;
            if(view instanceof android.view.ViewGroup){Button found=findRefresh((android.view.ViewGroup)view);if(found!=null)return found;}
        }
        return null;
    }

}
