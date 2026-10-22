package com.instagram.insfav.fetcher;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

final class QueueStore extends SQLiteOpenHelper {
    QueueStore(Context context) { super(context, "fetcher-queue.db", null, 5); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) { db.execSQL("CREATE TABLE accounts (username TEXT PRIMARY KEY, position INTEGER NOT NULL, status TEXT NOT NULL, code TEXT NOT NULL DEFAULT '', eligible INTEGER NOT NULL DEFAULT 1, attempts INTEGER NOT NULL DEFAULT 0)"); db.execSQL("CREATE INDEX account_work_order ON accounts(status,position)");createCatalog(db); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) db.execSQL("ALTER TABLE accounts ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE INDEX IF NOT EXISTS account_work_order ON accounts(status,position)");createCatalog(db);
    }
    private static void createCatalog(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS fetch_images (username TEXT PRIMARY KEY,uri TEXT NOT NULL,name TEXT NOT NULL,size INTEGER,width INTEGER,height INTEGER,modified INTEGER,readable INTEGER,needs_check INTEGER DEFAULT 0,checked_at INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS fetch_image_uri ON fetch_images(uri)");
        db.execSQL("CREATE TABLE IF NOT EXISTS fetch_meta (name TEXT PRIMARY KEY,value TEXT NOT NULL)");
        try{db.execSQL("ALTER TABLE accounts ADD COLUMN phase TEXT NOT NULL DEFAULT 'PENDING'");}catch(SQLiteException existing){/* Existing schema has the column. */}
        db.execSQL("CREATE INDEX IF NOT EXISTS account_phase ON accounts(phase)");
        // Private outcome ledger survives collection removals; never exported with account identifiers.
        db.execSQL("CREATE TABLE IF NOT EXISTS fetch_outcomes (username TEXT PRIMARY KEY,status TEXT NOT NULL,code TEXT NOT NULL,attempts INTEGER NOT NULL,recorded_at INTEGER NOT NULL)");
        db.execSQL("INSERT OR IGNORE INTO fetch_outcomes SELECT username,status,code,attempts,0 FROM accounts WHERE status IN ('SKIPPED_UNAVAILABLE','SKIPPED_NO_IMAGE','FAILED') OR (status='RETRY_LATER' AND attempts>0)");
        db.execSQL("UPDATE accounts SET phase=CASE WHEN status IN ('SUCCESS','EXISTING') THEN 'COMPLETED' WHEN status LIKE 'SKIPPED%' THEN 'SKIPPED' WHEN status='FAILED' THEN 'FAILED' ELSE phase END WHERE phase='PENDING'");
    }
    String meta(String name){try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM fetch_meta WHERE name=?",new String[]{name})){return c.moveToFirst()?c.getString(0):"";}}
    void meta(String name,String value){ContentValues v=new ContentValues();v.put("name",name);v.put("value",value);getWritableDatabase().insertWithOnConflict("fetch_meta",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    static final class Image {
        String user,uri,name;long size,modified;int width,height;boolean readable,needsCheck;
        Image(String user,String uri,String name,long size,int width,int height,long modified,boolean readable){this.user=user;this.uri=uri;this.name=name;this.size=size;this.width=width;this.height=height;this.modified=modified;this.readable=readable;}
    }
    Image image(String user){try(Cursor c=getReadableDatabase().rawQuery("SELECT uri,name,size,width,height,modified,readable,needs_check FROM fetch_images WHERE username=?",new String[]{user})){if(!c.moveToFirst())return null;Image v=new Image(user,c.getString(0),c.getString(1),c.getLong(2),c.getInt(3),c.getInt(4),c.getLong(5),c.getInt(6)!=0);v.needsCheck=c.getInt(7)!=0;return v;}}
    void image(Image i){
        ContentValues v=new ContentValues();v.put("username",i.user);v.put("uri",i.uri);v.put("name",i.name);v.put("size",i.size);v.put("width",i.width);v.put("height",i.height);v.put("modified",i.modified);v.put("readable",i.readable?1:0);v.put("needs_check",0);v.put("checked_at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("fetch_images",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    void invalidateUri(String uri){getWritableDatabase().execSQL("UPDATE fetch_images SET needs_check=1 WHERE uri=?",new Object[]{uri});getWritableDatabase().execSQL("UPDATE accounts SET status='PENDING',phase='PENDING' WHERE username IN (SELECT username FROM fetch_images WHERE uri=? AND needs_check=1) AND status IN ('SUCCESS','EXISTING')",new Object[]{uri});}
    void recoverInterrupted(){getWritableDatabase().execSQL("UPDATE fetch_images SET needs_check=1 WHERE username IN (SELECT username FROM accounts WHERE phase='DOWNLOADING')");getWritableDatabase().execSQL("UPDATE accounts SET phase='PENDING' WHERE phase='DOWNLOADING'");}
    boolean claim(String user){ContentValues v=new ContentValues();v.put("phase","DOWNLOADING");return getWritableDatabase().update("accounts",v,"username=? AND phase!='DOWNLOADING' AND (status='PENDING' OR (status='RETRY_LATER' AND attempts=0))",new String[]{user})==1;}
    void settleImages(){getWritableDatabase().execSQL("UPDATE accounts SET status='EXISTING',phase='COMPLETED',code='LOCAL_IMAGE' WHERE username IN (SELECT username FROM fetch_images WHERE readable=1 AND needs_check=0) AND status NOT IN ('SUCCESS','EXISTING')");}
    Map<String,Integer> phases(){Map<String,Integer> out=new HashMap<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT phase,COUNT(*) FROM accounts GROUP BY phase",null)){while(c.moveToNext())out.put(c.getString(0),c.getInt(1));}return out;}
    void sync(Collection<String> users) {
        Set<String> normalized=new LinkedHashSet<>();for(String user:users){String u=Identity.normalize(user);if(Identity.valid(u))normalized.add(u);}
        String signature=String.join("\n",normalized);if(signature.equals(meta("users")))return;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Set<String> wanted=new LinkedHashSet<>(normalized);
            for(String old:users(false)) if(!wanted.contains(old)) db.delete("accounts","username=?",new String[]{old});
            int position=0;
            for(String user:wanted) if(Identity.valid(user)) {
                ContentValues v=new ContentValues();v.put("username",user);v.put("position",position++);v.put("status","PENDING");long inserted=db.insertWithOnConflict("accounts",null,v,SQLiteDatabase.CONFLICT_IGNORE);
                if(inserted!=-1)db.execSQL("UPDATE accounts SET status=(SELECT status FROM fetch_outcomes WHERE username=?),code=(SELECT code FROM fetch_outcomes WHERE username=?),attempts=(SELECT attempts FROM fetch_outcomes WHERE username=?),phase=CASE WHEN (SELECT status FROM fetch_outcomes WHERE username=?) LIKE 'SKIPPED%' THEN 'SKIPPED' WHEN (SELECT status FROM fetch_outcomes WHERE username=?)='FAILED' THEN 'FAILED' ELSE 'PENDING' END WHERE username=? AND EXISTS(SELECT 1 FROM fetch_outcomes WHERE username=?)",new Object[]{user,user,user,user,user,user,user});
                v=new ContentValues();v.put("position",position-1);db.update("accounts",v,"username=?",new String[]{user});
            }
            meta("users",signature);settleImages();db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void requeueLost(String user){getWritableDatabase().execSQL("UPDATE accounts SET status='PENDING',code='',attempts=0,phase='PENDING' WHERE username=? AND status IN ('SUCCESS','EXISTING')",new Object[]{user});}
    List<String> completedImages(){ArrayList<String> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT username FROM accounts WHERE status IN ('SUCCESS','EXISTING')",null)){while(c.moveToNext())out.add(c.getString(0));}return out;}
    int attempts(String user) {try(Cursor c=getReadableDatabase().rawQuery("SELECT attempts FROM accounts WHERE username=?",new String[]{user})){return c.moveToFirst()?c.getInt(0):0;}}
    void attempted(String user) {getWritableDatabase().execSQL("UPDATE accounts SET attempts=attempts+1 WHERE username=?",new String[]{user});}
    void retrySkipped(){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
        db.execSQL("DELETE FROM fetch_outcomes WHERE username IN (SELECT username FROM accounts WHERE status IN ('SKIPPED_UNAVAILABLE','SKIPPED_NO_IMAGE','FAILED','RETRY_LATER'))");
        db.execSQL("UPDATE accounts SET status='PENDING',phase='PENDING',code='',attempts=0 WHERE status IN ('SKIPPED_UNAVAILABLE','SKIPPED_NO_IMAGE','FAILED','RETRY_LATER')");db.setTransactionSuccessful();
    }finally{db.endTransaction();}}
    // Start Missing does not erase failure history; retry_skipped is an explicit user action.
    void prepareMissing() {getWritableDatabase().execSQL("UPDATE accounts SET eligible=1 WHERE status='PENDING'");}
    void deferClaims(String code) {getWritableDatabase().execSQL("UPDATE accounts SET status='RETRY_LATER',phase='PENDING',code=? WHERE phase='DOWNLOADING'", new Object[]{code});}
    String next(){
        for(String condition:new String[]{"status='PENDING'","status='RETRY_LATER' AND attempts=0"})try(Cursor c=getReadableDatabase().rawQuery("SELECT username FROM accounts WHERE "+condition+" ORDER BY position LIMIT 1",null)){if(c.moveToFirst())return c.getString(0);}return null;
    }
    List<String> eligible() {
        ArrayList<String> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT username FROM accounts WHERE status='PENDING' OR (status='RETRY_LATER' AND attempts=0) ORDER BY CASE WHEN status='PENDING' THEN 0 ELSE 1 END,position",null)){while(c.moveToNext())result.add(c.getString(0));}return result;
    }
    List<String> users(boolean failedOnly) {
        ArrayList<String> users = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("accounts", new String[]{"username"}, failedOnly ? "status='FAILED'" : null, null, null, null, "position")) {
            while (cursor.moveToNext()) users.add(cursor.getString(0));
        }
        return users;
    }
    void mark(String user, String status, String code) {
        ContentValues values = new ContentValues(); values.put("status", status); values.put("code", code);
        values.put("phase",status.equals("SUCCESS")||status.equals("EXISTING")?"COMPLETED":status.startsWith("SKIPPED")?"SKIPPED":code.equals("RATE_LIMITED")?"RATE_LIMITED":status.equals("RETRY_LATER")?"PENDING":status);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            db.update("accounts", values, "username=?", new String[]{user});
            if(status.startsWith("SKIPPED")||status.equals("FAILED")||status.equals("RETRY_LATER")&&attempts(user)>0)
                db.execSQL("INSERT OR REPLACE INTO fetch_outcomes SELECT username,status,code,attempts,? FROM accounts WHERE username=?",new Object[]{System.currentTimeMillis(),user});
            else if(status.equals("SUCCESS")||status.equals("EXISTING"))db.delete("fetch_outcomes","username=?",new String[]{user});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    int held(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM accounts WHERE status='RETRY_LATER' AND attempts>0",null)){return c.moveToFirst()?c.getInt(0):0;}}
    int recordedOutcomes(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM fetch_outcomes",null)){return c.moveToFirst()?c.getInt(0):0;}}
    List<String> pending() {
        ArrayList<String> users = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT username FROM accounts WHERE status='PENDING' OR (status='RETRY_LATER' AND attempts=0) ORDER BY position", null)) { while (cursor.moveToNext()) users.add(cursor.getString(0)); }
        return users;
    }
    void reset(List<String> users) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { db.execSQL("UPDATE accounts SET eligible=0"); for (String user : users) { mark(user, "PENDING", ""); ContentValues v = new ContentValues(); v.put("eligible", 1); db.update("accounts", v, "username=?", new String[]{user}); } db.setTransactionSuccessful(); } finally { db.endTransaction(); }
    }
    Map<String, Integer> failures() {
        Map<String, Integer> result = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code,COUNT(*) FROM accounts WHERE status IN ('FAILED','RETRY_LATER') GROUP BY code ORDER BY COUNT(*) DESC", null)) { while(c.moveToNext()) result.put(c.getString(0), c.getInt(1)); }
        return result;
    }
    Map<String, Integer> counts() {
        Map<String, Integer> result = new HashMap<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT status,COUNT(*) FROM accounts GROUP BY status", null)) {
            while (cursor.moveToNext()) result.put(cursor.getString(0), cursor.getInt(1));
        }
        return result;
    }
}
