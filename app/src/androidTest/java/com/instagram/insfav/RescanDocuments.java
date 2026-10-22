package com.instagram.insfav;

import android.content.*;
import android.database.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only synthetic provider in the test APK. It cannot access a user's thumbnail folder. */
public final class RescanDocuments extends DocumentsProvider {
    static final String AUTH="com.instagram.insfav.plus.test.rescan";
    static Uri tree(){return DocumentsContract.buildTreeDocumentUri(AUTH,"root");}
    private File root;
    private final AtomicInteger opens=new AtomicInteger(),queries=new AtomicInteger(),writes=new AtomicInteger();
    @Override public boolean onCreate(){root=new File(getContext().getFilesDir(),"rescan-15000");return root.mkdirs()||root.isDirectory();}
    private File file(String id)throws FileNotFoundException {
        if("root".equals(id))return root;
        if(!id.matches("root/fixture_[0-9]+\\.webp"))throw new FileNotFoundException();
        return new File(root,id.substring(5));
    }
    private static final String[] C={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    private void row(MatrixCursor c,String id,File f) {
        MatrixCursor.RowBuilder row=c.newRow();
        for(String column:c.getColumnNames()) {
            Object v=null;
            if(C[0].equals(column))v=id;
            else if(C[1].equals(column))v=f.isDirectory()?".thumbnails":f.getName();
            else if(C[2].equals(column))v=f.isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:"image/webp";
            else if(C[3].equals(column))v=f.length();else if(C[4].equals(column))v=f.lastModified();
            row.add(v);
        }
    }
    @Override public Cursor queryRoots(String[] p){return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID});}
    @Override public Cursor queryDocument(String id,String[] p)throws FileNotFoundException {
        queries.incrementAndGet();MatrixCursor c=new MatrixCursor(p==null?C:p);File f=file(id);if(!f.exists())throw new FileNotFoundException();row(c,id,f);return c;
    }
    @Override public Cursor queryChildDocuments(String id,String[] p,String sort)throws FileNotFoundException {
        queries.incrementAndGet();MatrixCursor c=new MatrixCursor(p==null?C:p);File[] files=file(id).listFiles();
        if(files!=null)for(File f:files)row(c,"root/"+f.getName(),f);return c;
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {
        if(!"r".equals(mode)){writes.incrementAndGet();throw new FileNotFoundException("Read-only fixture");}
        opens.incrementAndGet();return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public void deleteDocument(String id)throws FileNotFoundException {writes.incrementAndGet();throw new FileNotFoundException("Read-only fixture");}
    @Override public boolean isChildDocument(String parent,String child){return "root".equals(parent)&&child.startsWith("root/");}
    @Override public Bundle call(String method,String arg,Bundle extras) {
        if(!"fixture.stats".equals(method))return super.call(method,arg,extras);
        Bundle b=new Bundle();b.putInt("opens",opens.get());b.putInt("queries",queries.get());b.putInt("writes",writes.get());
        try {
            File[] files=root.listFiles();if(files==null)throw new IOException();Arrays.sort(files,Comparator.comparing(File::getName));
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[4096];
            for(File f:files) {
                digest.update(f.getName().getBytes(StandardCharsets.UTF_8));digest.update(Long.toString(f.lastModified()).getBytes(StandardCharsets.UTF_8));
                try(InputStream in=new FileInputStream(f)){int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
            }
            b.putInt("files",files.length);b.putString("fingerprint",Base64.getEncoder().encodeToString(digest.digest()));
        }catch(Exception e){throw new IllegalStateException("Fixture fingerprint failed",e);}
        return b;
    }
    public static final class Grant extends BroadcastReceiver {
        @Override public void onReceive(Context c,Intent intent) {
            PendingResult pending=goAsync();
            new Thread(()->{
                try {
                    File root=new File(c.getFilesDir(),"rescan-15000");if(!root.mkdirs()&&!root.isDirectory())throw new IOException();
                    byte[][] encoded=new byte[2][];
                    for(int n=0;n<2;n++) {
                        Bitmap b=Bitmap.createBitmap(n==0?128:256,n==0?128:256,Bitmap.Config.ARGB_8888);
                        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){if(!b.compress(Bitmap.CompressFormat.WEBP,90,out))throw new IOException();encoded[n]=out.toByteArray();}finally{b.recycle();}
                    }
                    for(int n=0;n<15000;n++){File f=new File(root,"fixture_"+n+".webp");if(!f.exists())try(FileOutputStream out=new FileOutputStream(f)){out.write(encoded[n%2]);}}
                    c.grantUriPermission("com.instagram.insfav.plus",tree(),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                }catch(Exception e){android.util.Log.e("RescanFixture","Fixture setup failed",e);}
                finally{pending.finish();}
            },"seed-rescan-fixture").start();
        }
    }
}
