package com.instagram.insfav.fetcher;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HdThumbnailWriterTest {
    FetcherStorageTest harness;
    Context context;
    File folder;
    AtomicInteger indexed = new AtomicInteger();
    CountDownLatch written = new CountDownLatch(1);
    Fetcher.Host host;

    @Before public void setup() throws Exception {
        harness = new FetcherStorageTest(); harness.setup(); context = harness.context;
        folder = new File(harness.provider.root, ".thumbnails"); folder.mkdir();
        SessionState.prefs(context).edit().putString("tree", harness.tree.toString())
            .putString("directoryId", "root/.thumbnails").putInt("minimum", 1).putInt("maximum", 1).commit();
        host = new Fetcher.Host() {
            public Collection<String> users() { return Collections.singleton("example"); }
            public String tree() { return harness.tree.toString(); }
            public String directory() { return "root/.thumbnails"; }
            public long revision() { return indexed.get(); }
            public boolean hasImage(String user) { return indexed.get() > 0; }
            public void imageWritten(String user, Uri uri, String tree) throws Exception {
                assertEquals("example", user); assertEquals(tree(), tree);
                assertTrue(ThumbnailCodec.valid(OutputFolder.read(context.getContentResolver().openInputStream(uri))));
                indexed.incrementAndGet(); written.countDown();
            }
        };
    }
    byte[] saved() throws Exception { return Files.readAllBytes(new File(folder, "example.webp").toPath()); }
    void save(byte[] source) { HdThumbnailWriter.save(context, host, "example", source); }
    void assertOnlyFinal() { assertArrayEquals(new String[]{"example.webp"}, folder.list()); }

    @Test public void createsLossy256WebpIndexesOnceAndClearsOnlyItsOwnedCopy() throws Exception {
        byte[] remote = CodecTest.png(640, 480), copy = remote.clone(); save(copy);
        assertTrue(ThumbnailCodec.valid(saved()));
        Bitmap bitmap = BitmapFactory.decodeByteArray(saved(), 0, saved().length);
        assertEquals(256, bitmap.getWidth()); assertEquals(256, bitmap.getHeight()); bitmap.recycle();
        byte[] webp = saved(); boolean lossy = false;
        for (int offset = 12; offset + 8 <= webp.length;) {
            String chunk = new String(webp, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            assertNotEquals("VP8L", chunk); lossy |= "VP8 ".equals(chunk);
            int size = java.nio.ByteBuffer.wrap(webp, offset + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            offset += 8 + size + (size & 1);
        }
        assertTrue(lossy);
        assertEquals(1, indexed.get()); assertOnlyFinal();
        for (byte b : copy) assertEquals(0, b);
        assertNotEquals(0, remote[0]);
        long modified = new File(folder, "example.webp").lastModified(); byte[] first = saved();
        save(CodecTest.png(900, 900));
        assertArrayEquals(first, saved()); assertEquals(modified, new File(folder, "example.webp").lastModified());
        assertEquals(1, indexed.get()); assertOnlyFinal();
    }
    @Test public void preservesExistingLegacyAndCaseVariantWithoutEvenConvertingNewBytes() throws Exception {
        Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.WEBP,78,out); b.recycle();
        File keep = new File(folder,"@Example.WebP"); Files.write(keep.toPath(),out.toByteArray());
        long modified = keep.lastModified(); save(new byte[]{1,2,3});
        assertArrayEquals(out.toByteArray(), Files.readAllBytes(keep.toPath())); assertEquals(modified,keep.lastModified());
        assertFalse(new File(folder,"example.webp").exists()); assertEquals(0,indexed.get());
    }
    @Test public void invalidOrSmallImagesCannotCreateOrReplaceFiles() throws Exception {
        for (byte[] input : new byte[][]{new byte[0],new byte[]{1,2,3},CodecTest.png(128,128),Arrays.copyOf(CodecTest.png(600,600),40)}) save(input);
        assertEquals(0,folder.list().length); assertEquals(0,indexed.get());
        Files.write(new File(folder,"example.webp").toPath(),new byte[]{7});
        save(CodecTest.png(640,480)); assertArrayEquals(new byte[]{7},saved());
    }
    @Test public void centerCropDoesNotStretchOrUndersampleLongImages() throws Exception {
        Bitmap image = Bitmap.createBitmap(4096,512,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.drawColor(Color.RED); Paint p = new Paint(); p.setColor(Color.GREEN);
        canvas.drawRect(1792,0,2304,512,p); p.setColor(Color.BLUE); canvas.drawRect(1920,128,2176,384,p);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); image.compress(Bitmap.CompressFormat.PNG,100,output); image.recycle();
        save(output.toByteArray()); Bitmap result = BitmapFactory.decodeByteArray(saved(),0,saved().length);
        assertTrue(Color.green(result.getPixel(10,10)) > 200);
        assertTrue(Color.blue(result.getPixel(128,128)) > 200);
        assertTrue(Color.green(result.getPixel(210,128)) > 200); result.recycle();
    }
    @Test public void deniedPermissionAndUnsupportedAtomicRenameLeaveNoFinalOrPartial() throws Exception {
        harness.provider.rejectRename = true; save(CodecTest.png(640,480));
        assertEquals(0,folder.list().length); assertEquals(0,indexed.get());
        context.getContentResolver().releasePersistableUriPermission(harness.tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        save(CodecTest.png(640,480)); assertEquals(0,folder.list().length); assertEquals(0,indexed.get());
    }
    @Test public void concurrentViewerAndFetcherWritesPreserveTheFirstCompleteFile() throws Exception {
        byte[] remote = CodecTest.png(640,480), fetcher = ThumbnailCodec.convert(CodecTest.png(500,700));
        ExecutorService pool = Executors.newFixedThreadPool(6); CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        for (int i=0;i<12;i++) tasks.add(pool.submit(() -> { try { start.await(); save(remote.clone()); } catch(Exception e){throw new RuntimeException(e);} }));
        tasks.add(pool.submit(() -> {try {start.await();new OutputFolder(context,harness.tree.toString()).commit("example.webp",fetcher,false);}catch(Exception e){throw new RuntimeException(e);}}));
        try { start.countDown(); for(Future<?> f:tasks) f.get(10,TimeUnit.SECONDS); } finally {pool.shutdownNow();}
        assertTrue(ThumbnailCodec.valid(saved())); assertOnlyFinal(); assertTrue(indexed.get() <= 1);
        byte[] first=saved(); save(remote.clone()); assertArrayEquals(first,saved());
    }
    @Test public void restartRecoversInterruptedPartWithoutTouchingAnExistingFinal() throws Exception {
        save(CodecTest.png(640,480)); byte[] first=saved();
        String nonce=UUID.randomUUID().toString(),part=".insfav-"+nonce+".part",backup=".insfav-"+nonce+".bak";
        Files.write(new File(folder,part).toPath(),new byte[]{1,2,3});
        JSONObject record=new JSONObject().put("tree",harness.tree.toString()).put("folder",android.provider.DocumentsContract.buildDocumentUriUsingTree(harness.tree,"root/.thumbnails").toString())
            .put("name","example.webp").put("pending",part).put("backup",backup);
        android.util.AtomicFile journal=new android.util.AtomicFile(new File(context.getFilesDir(),"fetcher-write-journal.json"));
        FileOutputStream out=journal.startWrite();out.write(record.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));journal.finishWrite(out);
        new OutputFolder(context,harness.tree.toString()); assertArrayEquals(first,saved()); assertOnlyFinal();
        assertFalse(journal.getBaseFile().exists());
    }
    @Test public void realRemoteCompletionSavesWithoutAnyAdditionalProviderRequest() throws Exception {
        remote(false,false);
    }
    @Test public void remoteViewerStillWorksWithoutWritePermission() throws Exception {
        remote(true,false);
    }
    @Test public void invalidRemoteResponseNeverBecomesReadyOrWritesAFile() throws Exception {
        remote(false,true);
    }
    void remote(boolean deny, boolean invalid) throws Exception {
        Fetcher.initialize(context,host);
        if(deny) context.getContentResolver().releasePersistableUriPermission(harness.tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        AtomicInteger requests=new AtomicInteger(),readyCount=new AtomicInteger();CountDownLatch ready=new CountDownLatch(1),failed=new CountDownLatch(1);
        byte[] remote=invalid?new byte[]{1,2,3}:CodecTest.png(640,480);
        FetchBroker broker=new FetchBroker(context,full->new ProfileProvider(){
            public byte[] fetch(String user){requests.incrementAndGet();return remote;}
            public byte[] fetch(String user,boolean highResolution){assertTrue(highResolution);return fetch(user);}
            public void cancel(){}
        },new TestTime());
        ReflectionHelpers.setStaticField(FetchBroker.class,"instance",broker);
        Files.write(new File(context.getFilesDir(),"fetcher-cookies.enc").toPath(),new byte[]{1});
        Fetcher.Listener listener=new Fetcher.Listener(){
            public void changed(){}
            public void fullReady(String user,String token,int w,int h){assertEquals(640,w);assertEquals(480,h);readyCount.incrementAndGet();ready.countDown();}
            public void fullFailed(String token,String code){failed.countDown();}
        };
        Fetcher.addListener(listener);
        try {
            Fetcher.view("example","fixture");
            if(invalid){assertTrue(failed.await(10,TimeUnit.SECONDS));assertEquals(0,readyCount.get());assertEquals(0,folder.list().length);}
            else {
                assertTrue(ready.await(10,TimeUnit.SECONDS));assertTrue(Fetcher.full("fixture").length>0);
                if(!deny){assertTrue(written.await(10,TimeUnit.SECONDS));assertTrue(ThumbnailCodec.valid(saved()));assertEquals(1,indexed.get());}
                else {assertEquals(0,folder.list().length);assertEquals(0,indexed.get());}
            }
            assertEquals(1,requests.get());
            byte[] held=Fetcher.full("fixture"); Fetcher.closeViewer();for(byte b:held)assertEquals(0,b);
        } finally {Fetcher.removeListener(listener);Fetcher.closeViewer();broker.cancelAll();}
    }
}
