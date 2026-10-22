package com.instagram.insfav;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ThumbnailScannerTest {
    private Context context; private Store store; private LocalImages images;
    private LocalImagesTest.FixtureProvider provider;
    private final Uri tree=Uri.parse("content://insfav.test/tree/root");
    @Before public void setup()throws Exception {
        LocalImagesTest harness=new LocalImagesTest();harness.setup();
        context=ReflectionHelpers.getField(harness,"context");store=ReflectionHelpers.getField(harness,"store");
        images=ReflectionHelpers.getField(harness,"images");provider=ReflectionHelpers.getField(harness,"provider");
        images.select(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    private void populate(int count)throws Exception {
        provider.files.clear();provider.names.clear();provider.add("root/base.webp","base.webp",256,256);
        File base=provider.files.remove("root/base.webp");provider.names.clear();
        for(int n=0;n<count;n++){String id="root/fixture_"+n+".webp";provider.files.put(id,base);provider.names.put(id,"fixture_"+n+".webp");}
    }
    private JSONObject metrics()throws Exception{return images.snapshot(false).getJSONObject("scan").getJSONObject("io");}
    @Test public void smallSourcesOpenOnceAndWarmScanDoesNotRewriteEverySqliteRow()throws Exception {
        images.scan((e,v)->{});assertEquals("completed",store.settings.optString("scanState"));assertEquals(3,provider.opens);
        long revision=store.index.getLong("revision");images.scan((e,v)->{});
        assertEquals(3,provider.opens);assertEquals(3,metrics().getInt("reused"));assertEquals(0,metrics().getInt("inspected"));
        assertEquals(2,metrics().getInt("transactions"));assertEquals(1,metrics().getInt("metadataQueries"));assertEquals(revision,store.index.getLong("revision"));
    }
    @Test public void interruptedFirstScanResumesCommittedBatchesAfterReopeningDatabase()throws Exception {
        populate(600);
        images.scan((e,v)->{
            if(e.equals("scan-progress") && v.optInt("processed")==0)org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300));
            if(e.equals("scan-progress") && v.optInt("processed")>=512)images.cancelScan();
        });
        assertEquals("interrupted",store.settings.optString("scanState"));assertEquals(512,provider.opens);assertFalse(store.index.has("profiles"));
        store=new Store(context);images=new LocalImages(context,store);images.scan((e,v)->{});
        assertEquals("completed",store.settings.optString("scanState"));assertEquals(600,provider.opens);
        assertEquals(600,store.index.getJSONObject("profiles").length());assertTrue(metrics().getBoolean("resumed"));assertEquals(512,metrics().getInt("reused"));
    }
    @Test public void failedListingKeepsPublicSnapshotAndCompletedCheckpoints()throws Exception {
        images.scan((e,v)->{});String previous=store.index.toString();populate(1100);provider.failMoveAt=700;int before=provider.opens;
        images.scan((e,v)->{});assertEquals("failed",store.settings.optString("scanState"));assertEquals(previous,store.index.toString());assertEquals(before+512,provider.opens);
        provider.failMoveAt=-1;images.scan((e,v)->{});assertEquals(before+1100,provider.opens);assertEquals(1100,store.index.getJSONObject("profiles").length());
    }
    @Test public void loadingProviderCannotReplaceCompletedIndexOrPruneItsRecords()throws Exception {
        images.scan((e,v)->{});String previous=store.index.toString();provider.loading=true;
        provider.files.remove("root/example.PNG");images.scan((e,v)->{});
        assertEquals("failed",store.settings.optString("scanState"));assertEquals(previous,store.index.toString());
        provider.loading=false;images.scan((e,v)->{});assertEquals("completed",store.settings.optString("scanState"));
        assertEquals(32,store.index.getJSONObject("profiles").getJSONObject("example").getInt("width"));
        try(ThumbnailScanIndex db=new ThumbnailScanIndex(context,store.root);Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries",null)){assertTrue(c.moveToFirst());assertEquals(2,c.getInt(0));}
        assertEquals(0,provider.deletes);assertEquals(0,provider.writes);
    }
    @Test public void unknownMetadataIsNotTreatedAsAnUnchangedFile()throws Exception {
        provider.unknownMetadata=true;images.scan((e,v)->{});int first=provider.opens;images.scan((e,v)->{});
        assertEquals(first*2,provider.opens);assertEquals(0,metrics().getInt("reused"));assertEquals(3,store.index.getInt("unknownSizes"));
    }
    @Test public void changedDuplicateCanBecomeWinnerWithoutReopeningOtherCandidates()throws Exception {
        images.scan((e,v)->{});int first=provider.opens;provider.add("root/Example.JPG","Example.JPG",128,128);
        images.scan((e,v)->{});assertEquals(first+1,provider.opens);assertEquals("Example.JPG",store.index.getJSONObject("profiles").getJSONObject("example").getString("name"));
    }
    @Test public void temporaryFileNotFoundIsRetriedEvenWhenSizeAndDateStayTheSame()throws Exception {
        provider.failOnceId="root/example.PNG";images.scan((e,v)->{});int first=provider.opens;
        assertEquals(32,store.index.getJSONObject("profiles").getJSONObject("example").getInt("width"));
        images.scan((e,v)->{});assertEquals(first+1,provider.opens);assertEquals(64,store.index.getJSONObject("profiles").getJSONObject("example").getInt("width"));
    }
    @Test public void memoryPressureStopsNewWorkAndPreservesCompletedPublicIndex()throws Exception {
        images.scan((e,v)->{});String previous=store.index.toString();populate(600);provider.memoryFailureId="root/fixture_0.webp";int before=provider.opens;
        images.scan((e,v)->{});assertEquals("interrupted",store.settings.optString("scanState"));assertEquals(previous,store.index.toString());
        assertTrue(provider.opens-before<ThumbnailScanner.BATCH_SIZE);assertFalse(images.scanning());
        provider.memoryFailureId="";images.scan((e,v)->{});assertEquals("completed",store.settings.optString("scanState"));
    }
    @Test public void batchFailureRollsBackAllRowsInsteadOfPublishingAPartialBatch()throws Exception {
        try(ThumbnailScanIndex db=new ThumbnailScanIndex(context,store.root)) {
            db.begin("fixture");db.getWritableDatabase().execSQL("CREATE TRIGGER reject_row BEFORE INSERT ON entries WHEN NEW.document_id='bad' BEGIN SELECT RAISE(ABORT,'fixture'); END");
            List<ThumbnailScanIndex.Entry> rows=Arrays.asList(new ThumbnailScanIndex.Entry("good","good.webp","content://fixture/good",20,1),new ThumbnailScanIndex.Entry("bad","bad.webp","content://fixture/bad",20,1));
            try{db.save(rows);fail("Expected SQLite batch failure");}catch(SQLiteException expected){}
            try(Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries",null)){assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0));}
            db.getWritableDatabase().execSQL("DROP TRIGGER reject_row");db.save(rows);
            assertEquals(2,db.catalog().files);
        }
    }
    @Test public void invalidPayloadIsNotAcceptedMerelyBecauseItHasAnImageHeader()throws Exception {
        provider.add("root/partial.webp","partial.webp",256,256);File f=provider.files.get("root/partial.webp");byte[] full=Files.readAllBytes(f.toPath());
        Files.write(f.toPath(),Arrays.copyOf(full,Math.min(30,full.length/2)));
        ImageCodec.Inspection result=ImageCodec.inspect(context.getContentResolver(),android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"root/partial.webp"));assertFalse(result.readable);
    }
    @Test public void clearIndexAlsoClearsCheckpointsAndCannotSilentlyReuseOldValidation()throws Exception {
        images.scan((e,v)->{});int first=provider.opens;images.clearIndex();images.scan((e,v)->{});
        assertEquals(first*2,provider.opens);assertEquals(0,metrics().getInt("reused"));
    }
    @Test public void largeJpegHeadersUseBoundedFallbackAndStillPreserveSourceBytes()throws Exception {
        File file=provider.files.get("root/Example.JPG");byte[] jpeg=Files.readAllBytes(file.toPath());
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            out.write(jpeg,0,2);byte[] payload=new byte[65500];
            for(int n=0;n<3;n++){out.write(new byte[]{(byte)0xff,(byte)0xef,(byte)(65502>>8),(byte)65502});out.write(payload);}
            out.write(jpeg,2,jpeg.length-2);Files.write(file.toPath(),out.toByteArray());
        }
        byte[] before=Files.readAllBytes(file.toPath());int opened=provider.opens;
        ImageCodec.Inspection result=ImageCodec.inspect(context.getContentResolver(),android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"root/Example.JPG"));
        assertTrue(result.readable);assertEquals(32,result.width);assertEquals(32,result.height);assertEquals(opened+2,provider.opens);
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));
    }
    @Test public void revokedGrantCannotReplaceLastCompletedIndex()throws Exception {
        images.scan((e,v)->{});String previous=store.index.toString();int opened=provider.opens;
        context.getContentResolver().releasePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);images.scan((e,v)->{});
        assertEquals("failed",store.settings.optString("scanState"));assertEquals(previous,store.index.toString());assertEquals(opened,provider.opens);assertEquals(0,provider.writes);
    }
    @Test public void canceledValidationNeverOpensTheSource()throws Exception {
        android.os.CancellationSignal signal=new android.os.CancellationSignal();signal.cancel();int before=provider.opens;
        try {ImageCodec.inspect(context.getContentResolver(),android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"root/Example.JPG"),signal);fail("Expected cancellation");}
        catch(android.os.OperationCanceledException expected){}
        assertEquals(before,provider.opens);
    }
    @Test public void allFileResolutionCountsPersistAcrossWarmScan()throws Exception {
        populate(600);images.scan((e,v)->{});assertEquals("completed",store.settings.optString("scanState"));
        assertTrue(metrics().getInt("peakValidations")<=2);
        assertTrue(images.snapshot(false).getJSONObject("resolution").getBoolean("complete"));
        provider.add("root/fixture_0.png","fixture_0.png",128,128);provider.add("root/unmatched.png","unmatched name.png",512,512);int opened=provider.opens;
        images.scan((e,v)->{});assertEquals(opened+2,provider.opens);assertEquals(2,images.snapshot(false).getJSONObject("resolution").getInt("count"));
        assertEquals(602,store.index.getInt("files"));assertEquals(600,store.index.getJSONObject("profiles").length());
        int before=provider.opens;images=new LocalImages(context,new Store(context));images.checkAccess();
        assertEquals(2,images.snapshot(false).getJSONObject("resolution").getInt("count"));images.scan((e,v)->{});assertEquals(before,provider.opens);assertEquals(0,metrics().getInt("inspected"));
        assertEquals(2,images.snapshot(false).getJSONObject("resolution").getInt("count"));assertEquals(0,provider.deletes);assertEquals(0,provider.writes);
    }
    @Test public void legacyCompletedCheckpointProvidesAllFileAuditWithoutSourceReads()throws Exception {
        provider.add("root/Example.webp","Example.webp",256,256);provider.add("root/strange.png","no username space.png",512,512);images.scan((e,v)->{});
        int expected=images.snapshot(false).getJSONObject("resolution").getInt("count");assertTrue(expected>=3);
        store.index.remove("resolutionAudit");store.replaceIndex(store.index);int opened=provider.opens;
        images=new LocalImages(context,new Store(context));images.checkAccess();int checkedQueries=provider.childQueries;
        JSONObject restored=images.snapshot(false).getJSONObject("resolution");assertEquals(expected,restored.getInt("count"));assertTrue(restored.getBoolean("complete"));
        images.snapshot(false);assertEquals(opened,provider.opens);assertEquals(checkedQueries,provider.childQueries);
    }
}
