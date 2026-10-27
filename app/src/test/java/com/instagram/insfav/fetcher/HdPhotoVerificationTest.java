package com.instagram.insfav.fetcher;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HdPhotoVerificationTest {
    FetcherStorageTest harness;Context c;File target;Fetcher.Host host;byte[] blue,red;
    static final String PHOTO="https://scontent.cdninstagram.com/v/t51.2885-19/123_456_789_n.jpg?stp=dst-jpg_s640x640&oh=first&oe=1";
    @Before public void setup()throws Exception {
        harness=new FetcherStorageTest();harness.setup();c=harness.context;
        File folder=new File(harness.provider.root,".thumbnails");assertTrue(folder.mkdir());target=new File(folder,"example.webp");
        SessionState.prefs(c).edit().putString("tree",harness.tree.toString()).putString("directoryId","root/.thumbnails").commit();
        host=new Fetcher.Host(){public Collection<String> users(){return Arrays.asList("example");}public String tree(){return harness.tree.toString();}public String directory(){return "root/.thumbnails";}public long revision(){return 0;}public boolean hasImage(String user){return target.isFile();}public void imageWritten(String user,Uri uri,String tree){}};
        Fetcher.initialize(c,host);blue=CodecTest.png(640,480,Color.BLUE);red=CodecTest.png(640,480,Color.RED);
    }
    PublicProfileProvider transfer(byte[] data,AtomicInteger bodies){return new PublicProfileProvider(url->new ProviderTest.Stub(url,200,""){
        @Override public String getContentType(){return "image/png";}
        @Override public InputStream getInputStream(){bodies.incrementAndGet();return new ByteArrayInputStream(data);}
        @Override public String getHeaderField(String key){return key.equalsIgnoreCase("ETag")?"\"photo\"":null;}
    },null,new File(c.getCacheDir(),"test-transfers"));}
    @Test public void profileMetadataIsStillReadAndSecurityFailureCannotBeHiddenByHdCache()throws Exception {
        AtomicInteger profiles=new AtomicInteger(),bodies=new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> photo=new java.util.concurrent.atomic.AtomicReference<>(PHOTO);
        java.util.concurrent.atomic.AtomicBoolean denied=new java.util.concurrent.atomic.AtomicBoolean();
        BrowserProfileProvider provider=new BrowserProfileProvider(c,CookieJar.parse("sessionid=OFFLINE_CACHE_TEST",System.currentTimeMillis()),url->{
            if(url.getHost().equals("www.instagram.com")){
                profiles.incrementAndGet();assertTrue(url.getPath().endsWith("web_profile_info/"));
                String json="{\"status\":\"ok\",\"data\":{\"user\":{\"username\":\"example\",\"id\":123,\"profile_pic_url_hd\":"+org.json.JSONObject.quote(photo.get())+"}}}";
                return new ProviderTest.Stub(url,denied.get()?401:200,json);
            }
            return new ProviderTest.Stub(url,200,""){@Override public String getContentType(){return "image/png";}@Override public InputStream getInputStream(){bodies.incrementAndGet();return new ByteArrayInputStream(blue);}};
        },()->{throw new AssertionError("Unexpected fallback");});
        try{
            provider.fetch("example",true);photo.set(PHOTO.replace("oh=first&oe=1","oh=second&oe=2"));provider.fetch("example",true);
            assertEquals(2,profiles.get());assertEquals(1,bodies.get());
            photo.set(PHOTO.replace("123_456_789","987_654_321"));provider.fetch("example",true);assertEquals(3,profiles.get());assertEquals(2,bodies.get());
            denied.set(true);try{provider.fetch("example",true);fail();}catch(ProfileProvider.Failure e){assertEquals("COOKIE_INVALID_EXPIRED",e.code);}
            assertEquals(4,profiles.get());assertEquals(2,bodies.get());
        }finally{provider.close();}
    }
    @Test public void legacyBrowserResourceReusesOnlyVerifiedSameMediaObjectForSameProfile()throws Exception {
        HdImageCache.store(c,"example",new URL(PHOTO),blue,"");HdImageCache.Entry entry=HdImageCache.load(c,"example");
        android.webkit.WebResourceResponse response=HdImageCache.resource(entry,"example",PHOTO.replace("oh=first","oh=rotated"));assertNotNull(response);assertEquals("image/png",response.getMimeType());
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;while((n=response.getData().read(buffer))!=-1)out.write(buffer,0,n);assertArrayEquals(blue,out.toByteArray());
        assertNull(HdImageCache.resource(entry,"other",PHOTO));assertNull(HdImageCache.resource(entry,"example",PHOTO.replace("123_456_789","987_654_321")));
        assertNull(HdImageCache.resource(entry,"example",PHOTO+"&new_transform=1"));assertNull(HdImageCache.resource(entry,"example",PHOTO.replace("s640x640","s1080x1080")));
    }
    @Test public void freshSamePhotoIdWithRotatedSignatureReusesValidatedHdAcrossContexts()throws Exception {
        AtomicInteger bodies=new AtomicInteger();assertArrayEquals(blue,HdImageCache.fetch(c,"example",new URL(PHOTO),transfer(blue,bodies)));
        Context reopened=c.createPackageContext(c.getPackageName(),0);
        assertArrayEquals(blue,HdImageCache.fetch(reopened,"example",new URL(PHOTO.replace("oh=first&oe=1","oh=rotated&oe=2")),transfer(red,bodies)));
        assertEquals(1,bodies.get());
        // A new media object, changed transform, unknown query or another profile cannot reuse it.
        HdImageCache.fetch(c,"example",new URL(PHOTO.replace("123_456_789","999_456_789")),transfer(red,bodies));assertEquals(2,bodies.get());
        HdImageCache.fetch(c,"example",new URL(PHOTO.replace("s640x640","s1080x1080")),transfer(blue,bodies));assertEquals(3,bodies.get());
        HdImageCache.fetch(c,"other",new URL(PHOTO.replace("s640x640","s1080x1080")),transfer(blue,bodies));assertEquals(4,bodies.get());
    }
    @Test public void mutableUrlRequiresStrongEtagRevalidationAnd304NeverReadsBody()throws Exception {
        URL url=new URL("https://fixture.cdninstagram.com/avatar.png");HdImageCache.store(c,"example",url,blue,"\"v1\"");
        PublicProfileProvider unchanged=new PublicProfileProvider(u->new ProviderTest.Stub(u,304,""){
            @Override public int getResponseCode(){assertEquals("\"v1\"",getRequestProperty("If-None-Match"));assertNull(getRequestProperty("Cookie"));return 304;}
            @Override public InputStream getInputStream(){throw new AssertionError("304 has no image body");}
        });
        assertArrayEquals(blue,HdImageCache.fetch(c,"example",url,unchanged));
        AtomicInteger bodies=new AtomicInteger();assertArrayEquals(red,HdImageCache.fetch(c,"example",url,transfer(red,bodies)));assertEquals(1,bodies.get());
    }
    @Test public void noValidatorOnMutableUrlOrDamagedHdCacheForcesCurrentBody()throws Exception {
        AtomicInteger bodies=new AtomicInteger();URL mutable=new URL("https://fixture.cdninstagram.com/avatar.png");
        HdImageCache.store(c,"example",mutable,blue,"");assertArrayEquals(red,HdImageCache.fetch(c,"example",mutable,transfer(red,bodies)));assertEquals(1,bodies.get());
        HdImageCache.store(c,"example",new URL(PHOTO),blue,"");
        File[] cached=new File(c.getCacheDir(),"hd-viewer-cache").listFiles((d,n)->n.endsWith(".image"));assertNotNull(cached);assertEquals(1,cached.length);Files.write(cached[0].toPath(),new byte[]{1,2,3});
        assertArrayEquals(red,HdImageCache.fetch(c,"example",new URL(PHOTO),transfer(red,bodies)));assertEquals(2,bodies.get());
    }
    @Test public void sameHdAndVerifiedThumbnailSkipBothWriterAndConversionQueue()throws Exception {
        assertTrue(HdThumbnailWriter.save(c,host,"example",blue.clone()));
        assertTrue(HdThumbnailVerification.matches(c,host,"example",blue));
        byte[] old=Files.readAllBytes(target.toPath());int writes=harness.provider.writes,scans=harness.provider.childQueries;
        AtomicInteger progress=new AtomicInteger();Fetcher.Listener l=new Fetcher.Listener(){public void changed(){}public void fullReady(String u,String t,int w,int h){}public void fullFailed(String t,String code){}public void thumbnailProgress(String u,boolean busy){if(busy)progress.incrementAndGet();}};
        Fetcher.addListener(l);try{HdThumbnailJobs.enqueue(c,host,"example",blue);assertFalse(HdThumbnailJobs.busy(host,"example"));assertEquals(0,progress.get());assertEquals(scans,harness.provider.childQueries);}finally{Fetcher.removeListener(l);}
        assertFalse(HdThumbnailWriter.save(c,host,"example",blue.clone()));assertEquals(writes,harness.provider.writes);assertArrayEquals(old,Files.readAllBytes(target.toPath()));
    }
    @Test public void missingOrExternallyChangedThumbnailIsRebuiltAndNewPhotoReplacesOld()throws Exception {
        assertTrue(HdThumbnailWriter.save(c,host,"example",blue.clone()));byte[] original=Files.readAllBytes(target.toPath());
        Files.delete(target.toPath());assertFalse(HdThumbnailVerification.matches(c,host,"example",blue));assertTrue(HdThumbnailWriter.save(c,host,"example",blue.clone()));assertArrayEquals(original,Files.readAllBytes(target.toPath()));
        Files.write(target.toPath(),HdThumbnailWriter.encode(red));assertFalse(HdThumbnailVerification.matches(c,host,"example",blue));assertTrue(HdThumbnailWriter.save(c,host,"example",blue.clone()));
        assertFalse(HdThumbnailVerification.matches(c,host,"example",red));assertTrue(HdThumbnailWriter.save(c,host,"example",red.clone()));
        assertFalse(Arrays.equals(original,Files.readAllBytes(target.toPath())));assertTrue(HdThumbnailVerification.matches(c,host,"example",red));
    }
    @Test public void existingIdenticalThumbnailGetsVerificationReceiptWithoutBeingRewritten()throws Exception {
        Files.write(target.toPath(),HdThumbnailWriter.encode(blue));int writes=harness.provider.writes;
        assertFalse(HdThumbnailWriter.save(c,host,"example",blue.clone()));assertEquals(writes,harness.provider.writes);assertTrue(HdThumbnailVerification.matches(c,host,"example",blue));
    }
}
