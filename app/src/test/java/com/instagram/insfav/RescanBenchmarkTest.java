package com.instagram.insfav;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Same harness runs against the unmodified baseline and optimized scanner. Synthetic files only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RescanBenchmarkTest {
    @Test public void fifteenThousandRealFilesColdAndWarmWithoutSourceMutation() throws Exception {
        File root=Files.createTempDirectory("rescan-15000").toFile();
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){File f=new File(root,"private");f.mkdirs();return f;}
            @Override public File getCacheDir(){File f=new File(root,"cache");f.mkdirs();return f;}
            @Override public File getDatabasePath(String name){if(new File(name).isAbsolute())return new File(name);File f=new File(root,"databases");f.mkdirs();return new File(f,name);}
        };
        ProviderInfo info=new ProviderInfo();info.authority="rescan.fixture";info.exported=true;info.grantUriPermissions=true;
        Provider provider=Robolectric.buildContentProvider(Provider.class).create(info).get();
        byte[][] encodings=new byte[2][];
        for(int n=0;n<2;n++) {
            Bitmap b=Bitmap.createBitmap(n==0?128:256,n==0?128:256,Bitmap.Config.ARGB_8888);
            try(ByteArrayOutputStream out=new ByteArrayOutputStream()){assertTrue(b.compress(Bitmap.CompressFormat.WEBP,90,out));encodings[n]=out.toByteArray();}finally{b.recycle();}
        }
        File source=new File(root,"source");assertTrue(source.mkdir());
        JSONArray items=new JSONArray();
        for(int n=0;n<15000;n++) {
            String user="fixture_"+n,name=user+".webp";File f=new File(source,name);
            Files.write(f.toPath(),encodings[n%2]);provider.files.put(name,f);
            items.put(new JSONObject().put("pageId",user));
        }
        String original=new JSONArray().put(new JSONObject().put("name","Offline fixture").put("items",items)).toString();
        Store store=new Store(context);store.importDocument(original);LocalImages images=new LocalImages(context,store);
        Uri tree=Uri.parse("content://rescan.fixture/tree/root");images.select(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String before=fingerprint(provider.files);int queries=provider.queries.get();
        long start=System.nanoTime();images.scan((e,v)->{},false);long cold=(System.nanoTime()-start)/1000000;
        assertEquals("completed",store.settings.optString("scanState"));assertEquals(15000,images.snapshot(false).getInt("matched"));
        int coldOpens=provider.opens.get(),coldQueries=provider.queries.get()-queries;queries=provider.queries.get();
        int peak=provider.peak.get();provider.peak.set(0);int opened=provider.opens.get();
        start=System.nanoTime();images.scan((e,v)->{},false);long warm=(System.nanoTime()-start)/1000000;
        assertEquals("completed",store.settings.optString("scanState"));assertEquals(15000,images.snapshot(false).getInt("matched"));
        assertEquals(opened,provider.opens.get());assertEquals(original,store.original());
        assertEquals(before,fingerprint(provider.files));assertEquals(0,provider.writes.get());
        System.out.println("RESCAN_15000 "+new JSONObject().put("environment","Robolectric API34, 15000 real synthetic 128/256 WebP files; 1 ms simulated SAF open latency")
            .put("files",15000).put("coldMs",cold).put("warmMs",warm).put("coldOpens",coldOpens).put("warmOpens",provider.opens.get()-opened)
            .put("coldQueries",coldQueries).put("warmQueries",provider.queries.get()-queries).put("peakOpenCalls",peak)
            .put("sourceBytesAndDatesUnchanged",true).put("sourceWrites",provider.writes.get()));
    }
    static String fingerprint(Map<String,File> files)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(Map.Entry<String,File> e:files.entrySet()) {
            digest.update(e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(Long.toString(e.getValue().lastModified()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(e.getValue().toPath()));
        }
        return Base64.getEncoder().encodeToString(digest.digest());
    }
    public static class Provider extends ContentProvider {
        final Map<String,File> files=new LinkedHashMap<>();
        final AtomicInteger opens=new AtomicInteger(),queries=new AtomicInteger(),writes=new AtomicInteger(),active=new AtomicInteger(),peak=new AtomicInteger();
        volatile int openDelayMs=1;
        @Override public boolean onCreate(){return true;}
        @Override public Cursor query(Uri uri,String[] columns,String selection,String[] args,String sort) {
            queries.incrementAndGet();MatrixCursor c=new MatrixCursor(columns);
            if("children".equals(uri.getLastPathSegment()))for(Map.Entry<String,File> e:files.entrySet())row(c,columns,e.getKey(),e.getValue());
            else row(c,columns,".thumbnails",null);
            return c;
        }
        void row(MatrixCursor c,String[] columns,String name,File f) {
            Object[] row=new Object[columns.length];
            for(int n=0;n<columns.length;n++)switch(columns[n]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:row[n]=name;break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:row[n]=name;break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:row[n]=f==null?DocumentsContract.Document.MIME_TYPE_DIR:"image/webp";break;
                case DocumentsContract.Document.COLUMN_SIZE:row[n]=f==null?0:f.length();break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED:row[n]=f==null?1:f.lastModified();break;
            }
            c.addRow(row);
        }
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
            if(!"r".equals(mode)){writes.incrementAndGet();throw new FileNotFoundException("Read only");}
            opens.incrementAndGet();int running=active.incrementAndGet();peak.accumulateAndGet(running,Math::max);
            try {
                if(openDelayMs>0)Thread.sleep(openDelayMs);
                File f=files.get(DocumentsContract.getDocumentId(uri));if(f==null)throw new FileNotFoundException();
                return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new FileNotFoundException("Interrupted");}
            finally{active.decrementAndGet();}
        }
        @Override public String getType(Uri uri){return "image/webp";}
        @Override public Uri insert(Uri uri,ContentValues v){writes.incrementAndGet();throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues v,String where,String[] args){writes.incrementAndGet();throw new UnsupportedOperationException();}
        @Override public int delete(Uri uri,String where,String[] args){writes.incrementAndGet();throw new UnsupportedOperationException();}
    }
}
