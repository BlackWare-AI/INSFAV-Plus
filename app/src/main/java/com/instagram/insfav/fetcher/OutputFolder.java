package com.instagram.insfav.fetcher;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

/** SAF only. No broad storage permission and no queue files in the output folder. */
final class OutputFolder {
    private final ContentResolver resolver;
    private final QueueStore catalog;
    private final android.content.SharedPreferences preferences;
    boolean lastWriteCreated;
    private final android.util.AtomicFile journal;
    private final Uri tree, folder;
    private final Map<String, Uri> files = new HashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong CONTENT_REVISION=new java.util.concurrent.atomic.AtomicLong();private long seenRevision;
    OutputFolder(Context context,String treeValue)throws IOException{this(context,treeValue,null,false,()->{});}
    OutputFolder(Context context,String treeValue,QueueStore catalog,boolean verify,Runnable progress)throws IOException {
        this.catalog=catalog;preferences=SessionState.prefs(context);
        resolver = context.getContentResolver(); journal=new android.util.AtomicFile(new File(context.getFilesDir(),"fetcher-write-journal.json")); tree = Uri.parse(treeValue);
        boolean permission = false;
        for (UriPermission p : resolver.getPersistedUriPermissions()) if (p.getUri().equals(tree) && p.isReadPermission() && p.isWritePermission()) permission = true;
        if (!permission) throw new SecurityException("OUTPUT_PERMISSION_LOST");
        String directory=SessionState.prefs(context).getString("directoryId",DocumentsContract.getTreeDocumentId(tree));
        folder=DocumentsContract.buildDocumentUriUsingTree(tree,directory);
        try(Cursor c=resolver.query(folder,new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},null,null)){
            if(c==null||!c.moveToFirst()||!DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(0)))throw new IOException("OUTPUT_FOLDER_INVALID");
        }
        if(catalog==null)files.putAll(children(folder));
        else if(verify||!FetcherCatalog.ready(catalog,treeValue+"\n"+directory))verifyAll(treeValue+"\n"+directory,progress);
        synchronized(WRITE_LOCK){if(journal.getBaseFile().exists()){files.putAll(children(folder));recoverJournal();files.clear();if(catalog==null)files.putAll(children(folder));else verifyAll(treeValue+"\n"+directory,progress);}seenRevision=CONTENT_REVISION.get();}
    }
    private Map<String, Uri> children(Uri directory) throws IOException {
        Uri child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(directory));
        Map<String, Uri> result = new HashMap<>();
        try (Cursor cursor = resolver.query(child, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null)) {
            if (cursor == null) throw new IOException("OUTPUT_UNAVAILABLE");
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if(name.toLowerCase(Locale.ROOT).endsWith(".webp")){String user=Identity.normalize(name.substring(0,name.length()-5));if(Identity.valid(user))name=user+".webp";}
                if (result.containsKey(name)) {
                    // Multiple case variants refer to one account. Keep an existing readable candidate.
                    Uri next=DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));
                    boolean currentValid=ThumbnailCodec.valid(read(resolver.openInputStream(result.get(name))));
                    if(!currentValid&&ThumbnailCodec.valid(read(resolver.openInputStream(next))))result.put(name,next);
                    continue;
                }
                result.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)));
            }
        }
        return result;
    }
    static byte[] read(InputStream source) throws IOException {
        if (source == null) throw new IOException("OUTPUT_READ_FAILED");
        try (InputStream input = source; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) { if (bytes.size() + n > ThumbnailCodec.MAX_OUTPUT) throw new IOException("OUTPUT_SIZE_REJECTED"); bytes.write(buffer, 0, n); }
            return bytes.toByteArray();
        }
    }
    Uri uri(String filename)throws IOException {return lookup(filename);}
    private Uri lookup(String filename)throws IOException {
        if(catalog==null)return files.get(filename);
        String user=Identity.normalize(filename.substring(0,filename.length()-5));QueueStore.Image image=catalog.image(user);
        if(image==null)return refreshOne(filename);
        if(image.needsCheck){
            if(image.uri.isEmpty())return refreshOne(filename);
            try{QueueStore.Image checked=inspect(user,image.name,Uri.parse(image.uri),image.size,image.modified);catalog.image(checked);image=checked;}
            catch(FileNotFoundException missing){image=new QueueStore.Image(user,"",filename,0,0,0,0,false);catalog.image(image);}
        }else FetcherCatalog.hits.incrementAndGet();
        return image.uri.isEmpty()?null:Uri.parse(image.uri);
    }
    private Uri refreshOne(String filename)throws IOException {
        String user=Identity.normalize(filename.substring(0,filename.length()-5));Uri found=null;String name=filename;long size=-1,modified=0;
        Uri child=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(folder));
        String[] cols={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_MIME_TYPE};
        android.os.Bundle args=new android.os.Bundle();args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,DocumentsContract.Document.COLUMN_DISPLAY_NAME+"=?");args.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{filename});
        try(Cursor c=resolver.query(child,cols,args,null)){
            if(c==null)throw new IOException("OUTPUT_UNAVAILABLE");
            while(c.moveToNext()){
                String candidate=c.getString(1);if(DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(4))||candidate==null||!candidate.toLowerCase(Locale.ROOT).endsWith(".webp"))continue;
                if(!Identity.normalize(candidate.substring(0,candidate.length()-5)).equals(user))continue;
                name=candidate;found=DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0));size=c.isNull(2)?-1:c.getLong(2);modified=c.isNull(3)?0:c.getLong(3);
                QueueStore.Image checked=inspect(user,name,found,size,modified);catalog.image(checked);if(checked.readable)return found;
            }
        }
        if(found==null)catalog.image(new QueueStore.Image(user,"",filename,0,0,0,0,false));return found;
    }
    boolean valid(String filename) throws IOException {
        Uri file=lookup(filename);if(file==null)return false;
        if(catalog!=null){QueueStore.Image image=catalog.image(Identity.normalize(filename.substring(0,filename.length()-5)));return image!=null&&image.readable;}
        try {return ThumbnailCodec.valid(read(resolver.openInputStream(file)));}
        catch(FileNotFoundException error){throw new IOException("OUTPUT_UNAVAILABLE",error);}
        catch(IOException error){if("OUTPUT_SIZE_REJECTED".equals(error.getMessage()))return false;throw error;}
    }
    Fetcher.ImageRecord metadata(String user)throws IOException {
        QueueStore.Image image=catalog==null?null:catalog.image(user);
        if(image==null||!image.readable||image.uri.isEmpty())throw new IOException("OUTPUT_METADATA_MISSING");
        return new Fetcher.ImageRecord(user,image.uri,image.name,image.size,image.width,image.height,image.modified);
    }
    private QueueStore.Image inspect(String user,String name,Uri uri,long size,long modified)throws IOException {
        android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=resolver.openInputStream(uri)){if(in==null)throw new IOException("OUTPUT_UNAVAILABLE");FetcherCatalog.opens.incrementAndGet();FetcherCatalog.decodes.incrementAndGet();android.graphics.BitmapFactory.decodeStream(in,null,bounds);}
        boolean readable=false;
        if(bounds.outWidth>0&&bounds.outHeight>0&&(long)bounds.outWidth*bounds.outHeight<=40000000){android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/o.inSampleSize>256)o.inSampleSize*=2;
            try(InputStream in=resolver.openInputStream(uri)){if(in==null)throw new IOException("OUTPUT_UNAVAILABLE");FetcherCatalog.opens.incrementAndGet();FetcherCatalog.decodes.incrementAndGet();android.graphics.Bitmap b=android.graphics.BitmapFactory.decodeStream(in,null,o);readable=b!=null;if(b!=null)b.recycle();}}
        return new QueueStore.Image(user,uri.toString(),name,size,bounds.outWidth,bounds.outHeight,modified,readable);
    }
    private void verifyAll(String scope,Runnable progress)throws IOException {
        android.database.sqlite.SQLiteDatabase db=catalog.getWritableDatabase();db.beginTransaction();Set<String> seen=new HashSet<>();
        try {
            if(!scope.equals(catalog.meta("folder")))db.execSQL("UPDATE accounts SET status='PENDING',phase='PENDING',code='',attempts=0 WHERE status IN ('SUCCESS','EXISTING')");
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(folder));FetcherCatalog.listings.incrementAndGet();
            String[] cols={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_MIME_TYPE};
            try(Cursor c=resolver.query(children,cols,null,null)){
                if(c==null)throw new IOException("OUTPUT_UNAVAILABLE");int count=0,total=c.getCount();
                while(c.moveToNext()){
                    if(Thread.currentThread().isInterrupted()||QueueControl.requested)throw new IOException("INDEX_INTERRUPTED");
                    String name=c.getString(1);if(name!=null&&!DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(4))&&name.toLowerCase(Locale.ROOT).matches(".*\\.(webp|png|jpe?g)$")){
                        String user=Identity.normalize(name.substring(0,name.lastIndexOf('.')));if(Identity.valid(user)){
                            QueueStore.Image value=inspect(user,name,DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0)),c.isNull(2)?-1:c.getLong(2),c.isNull(3)?0:c.getLong(3));
                            QueueStore.Image old=catalog.image(user);if(!seen.contains(user)||old==null||!old.readable&&value.readable||old.readable==value.readable&&(long)value.width*value.height>(long)old.width*old.height)catalog.image(value);if(!value.readable)catalog.requeueLost(user);seen.add(user);
                        }
                    }
                    count++;if(count%64==0||count==total){preferences.edit().putInt("indexProcessed",count).putInt("indexTotal",total).apply();progress.run();}
                }
            }
            List<String> removed=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT username FROM fetch_images",null)){while(c.moveToNext())if(!seen.contains(c.getString(0)))removed.add(c.getString(0));}
            for(String user:removed){catalog.image(new QueueStore.Image(user,"","",0,0,0,0,false));catalog.requeueLost(user);}
            catalog.meta("folder",scope);catalog.meta("ready","1");catalog.meta("lastIndex",Long.toString(System.currentTimeMillis()));catalog.meta("verificationRecommended","0");catalog.settleImages();db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void recover(String filename) throws IOException {
        Uri partial = files.get(filename + ".part");
        if (partial != null) {
            if (!DocumentsContract.deleteDocument(resolver, partial)) throw new IOException("PARTIAL_CLEANUP_FAILED");
            files.remove(filename + ".part");
        }
    }
    private void exactName(Uri file, String name) throws IOException {
        try (Cursor c = resolver.query(file, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null)) {
            if (c == null || !c.moveToFirst() || !name.equals(c.getString(0))) throw new IOException("OUTPUT_FILENAME_CHANGED");
        }
    }
    private void saveJournal(String filename,String pending,String backup)throws IOException{
        FileOutputStream out=null;try{org.json.JSONObject value=new org.json.JSONObject().put("tree",tree.toString()).put("folder",folder.toString()).put("name",filename).put("pending",pending).put("backup",backup);out=journal.startWrite();out.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));journal.finishWrite(out);out=null;}catch(org.json.JSONException error){throw new IOException("OUTPUT_WRITE_FAILED");}finally{if(out!=null)journal.failWrite(out);}
    }
    private void recoverJournal()throws IOException{
        if(!journal.getBaseFile().exists())return;
        try{
            org.json.JSONObject value=new org.json.JSONObject(new String(journal.readFully(),java.nio.charset.StandardCharsets.UTF_8));
            if(!tree.toString().equals(value.optString("tree"))||!folder.toString().equals(value.optString("folder")))return;
            String name=value.getString("name"),backupName=value.getString("backup"),pendingName=value.getString("pending");
            if(!name.matches("[a-z0-9._]{1,30}\\.webp")||!backupName.matches("\\.insfav-[a-f0-9-]+\\.bak")||!pendingName.matches("\\.insfav-[a-f0-9-]+\\.part"))throw new IOException("OUTPUT_UNAVAILABLE");
            Uri backup=files.get(backupName),finalFile=files.get(name),pending=files.get(pendingName);
            if(backup!=null){
                if(finalFile!=null&&ThumbnailCodec.valid(read(resolver.openInputStream(finalFile))))DocumentsContract.deleteDocument(resolver,backup);
                else{if(finalFile!=null)DocumentsContract.deleteDocument(resolver,finalFile);if(DocumentsContract.renameDocument(resolver,backup,name)==null)throw new IOException("OUTPUT_REPLACE_FAILED");}
            }
            if(pending!=null)DocumentsContract.deleteDocument(resolver,pending);journal.delete();
        }catch(org.json.JSONException error){throw new IOException("OUTPUT_UNAVAILABLE",error);}
    }
    private static final Object WRITE_LOCK=new Object();
    boolean write(String filename,byte[] bytes)throws IOException{commit(filename,bytes,false);return true;}
    Uri commit(String filename,byte[] bytes,boolean replace)throws IOException{
        synchronized(WRITE_LOCK){
            if(!filename.matches("[a-z0-9._]{1,30}\\.webp")||!ThumbnailCodec.valid(bytes))throw new IOException("OUTPUT_INVALID_WEBP");
            lastWriteCreated=false;
            if(catalog!=null){refreshOne(filename);}
            if(catalog==null&&(seenRevision!=CONTENT_REVISION.get()||journal.getBaseFile().exists())){files.clear();files.putAll(children(folder));recoverJournal();files.clear();files.putAll(children(folder));seenRevision=CONTENT_REVISION.get();}
            if(valid(filename))return lookup(filename);
            String nonce=java.util.UUID.randomUUID().toString();String pendingName=".insfav-"+nonce+".part",backupName=".insfav-"+nonce+".bak";
            Uri pending=null,old=lookup(filename),backup=null,committed=null;boolean finalVerified=false;
            String writingUser=Identity.normalize(filename.substring(0,filename.length()-5));FetcherCatalog.writing.add(writingUser);
            try{
                pending=DocumentsContract.createDocument(resolver,folder,"application/octet-stream",pendingName);if(pending==null)throw new IOException("OUTPUT_CREATE_FAILED");exactName(pending,pendingName);
                try(OutputStream output=resolver.openOutputStream(pending,"wt")){if(output==null)throw new IOException("OUTPUT_WRITE_FAILED");output.write(bytes);output.flush();}
                if(!ThumbnailCodec.valid(read(resolver.openInputStream(pending))))throw new IOException("OUTPUT_READBACK_FAILED");
                saveJournal(filename,pendingName,backupName);
                if(old!=null){backup=DocumentsContract.renameDocument(resolver,old,backupName);if(backup==null)throw new IOException("ATOMIC_RENAME_UNSUPPORTED");exactName(backup,backupName);}
                committed=DocumentsContract.renameDocument(resolver,pending,filename);if(committed==null)throw new IOException("ATOMIC_RENAME_UNSUPPORTED");pending=null;exactName(committed,filename);
                if(!ThumbnailCodec.valid(read(resolver.openInputStream(committed))))throw new IOException("OUTPUT_FINAL_VERIFY_FAILED");
                finalVerified=true;
                // Only a verified new final file may replace the old valid content.
                if(backup!=null){try{DocumentsContract.deleteDocument(resolver,backup);}catch(Exception ignored){/* Retain backup rather than risk the final file. */}}
                journal.delete();files.put(filename,committed);lastWriteCreated=true;
                if(catalog!=null)catalog.image(new QueueStore.Image(Identity.normalize(filename.substring(0,filename.length()-5)),committed.toString(),filename,bytes.length,ThumbnailCodec.TARGET_SIZE,ThumbnailCodec.TARGET_SIZE,System.currentTimeMillis(),true));
                seenRevision=CONTENT_REVISION.incrementAndGet();return committed;
            }catch(Exception error){
                if(committed!=null&&!finalVerified)try{DocumentsContract.deleteDocument(resolver,committed);}catch(Exception ignored){}
                if(backup!=null&&!finalVerified)try{DocumentsContract.renameDocument(resolver,backup,filename);}catch(Exception ignored){/* Old bytes stay in the uniquely named backup, never deleted. */}
                throw error instanceof IOException?(IOException)error:new IOException("OUTPUT_REPLACE_FAILED",error);
            }finally{if(finalVerified&&committed!=null)FetcherCatalog.ownUris.put(committed.toString(),System.currentTimeMillis()+3000);FetcherCatalog.writing.remove(writingUser);if(pending!=null)try{DocumentsContract.deleteDocument(resolver,pending);}catch(Exception ignored){}}
        }
    }
}
