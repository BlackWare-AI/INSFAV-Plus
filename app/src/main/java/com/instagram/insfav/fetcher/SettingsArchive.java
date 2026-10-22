package com.instagram.insfav.fetcher;

import android.content.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.IOException;

/** Portable authenticated settings backup. Favorites, queue, index and runtime state are excluded. */
// Queue, session, schedule and recovery decisions must be durable before continuing.
@android.annotation.SuppressLint("ApplySharedPref")
public final class SettingsArchive {
    private static final String FORMAT="INSFAV_SETTINGS";
    private static final int ITERATIONS=210000;
    private static final String[] INTS={"minimum","maximum","startMinute","endMinute","concurrentWebViews"};
    public static String export(Context c,char[] password,JSONObject extra)throws Exception {return export(c,password,extra,new CookieVault(c));}
    static String export(Context c,char[] password,JSONObject extra,CookieVault vault)throws Exception {
        SharedPreferences p=SessionState.prefs(c);RunPolicy policy=RunPolicy.read(p);
        JSONObject prefs=new JSONObject().put("minimum",policy.minimum).put("maximum",policy.maximum).put("startMinute",policy.start).put("endMinute",policy.end).put("concurrentWebViews",p.getInt("concurrentWebViews",1)).put("background",p.getBoolean("background",false));
        JSONObject data=new JSONObject().put("preferences",prefs).put("extra",extra).put("cookies",vault.configured()?vault.stored():JSONObject.NULL);
        return encrypt(c.getPackageName(),data,password);
    }
    static String encrypt(String app,JSONObject data,char[] password)throws Exception {
        if(password.length<8)throw new IOException("Use a password with at least 8 characters.");
        byte[] salt=new byte[16],iv=new byte[12],plain=data.toString().getBytes(StandardCharsets.UTF_8);
        SecureRandom random=new SecureRandom();random.nextBytes(salt);random.nextBytes(iv);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(password,salt),new GCMParameterSpec(128,iv));cipher.updateAAD((FORMAT+":1:"+app).getBytes(StandardCharsets.UTF_8));
            return new JSONObject().put("format",FORMAT).put("version",1).put("application",app).put("kdf","PBKDF2-HMAC-SHA256").put("iterations",ITERATIONS).put("cipher","AES-256-GCM").put("salt",enc(salt)).put("iv",enc(iv)).put("data",enc(cipher.doFinal(plain))).toString();
        } finally {Arrays.fill(plain,(byte)0);}
    }
    public static JSONObject inspect(Context c,String raw,char[] password)throws Exception {
        JSONObject data=decrypt(c.getPackageName(),raw,password);validate(data);return data;
    }
    /** Deliberate migration only; ordinary import never tries another application's AAD. */
    public static JSONObject inspectOriginal(Context c,String raw,char[] password)throws Exception {
        if(!"com.instagram.insfav.plus".equals(c.getPackageName()))throw new IOException("Unsupported destination application.");
        JSONObject data=decrypt("com.instagram.insfav",raw,password);validate(data);return data;
    }
    public interface Extras {void apply()throws Exception;void rollback()throws Exception;}
    private static final Extras NO_EXTRAS=new Extras(){public void apply(){}public void rollback(){}};
    public static void apply(Context c,JSONObject data,Extras extras)throws Exception {apply(c,data,new CookieVault(c),extras);}
    static JSONObject decrypt(String app,String raw,char[] password)throws Exception {
        if(raw.length()>1024*1024)throw new IOException("Settings backup is too large.");
        try {
            JSONObject box=new JSONObject(raw);
            if(!FORMAT.equals(box.getString("format"))||box.getInt("version")!=1||!app.equals(box.getString("application"))||box.getInt("iterations")!=ITERATIONS||!"PBKDF2-HMAC-SHA256".equals(box.getString("kdf"))||!"AES-256-GCM".equals(box.getString("cipher")))throw new IOException("Unsupported settings backup.");
            byte[] salt=Base64.getDecoder().decode(box.getString("salt")),iv=Base64.getDecoder().decode(box.getString("iv"));
            if(salt.length!=16||iv.length!=12)throw new IOException("Invalid settings backup.");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(password,salt),new GCMParameterSpec(128,iv));cipher.updateAAD((FORMAT+":1:"+app).getBytes(StandardCharsets.UTF_8));
            byte[] plain=cipher.doFinal(Base64.getDecoder().decode(box.getString("data")));
            try{return new JSONObject(new String(plain,StandardCharsets.UTF_8));}finally{Arrays.fill(plain,(byte)0);}
        }catch(Exception error){throw new IOException("Wrong password, damaged backup, or incompatible application.");}
    }
    private static SecretKeySpec key(char[] password,byte[] salt)throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);byte[] bytes=null;
        try{bytes=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();return new SecretKeySpec(bytes,"AES");}
        finally{spec.clearPassword();if(bytes!=null)Arrays.fill(bytes,(byte)0);}
    }
    private static String enc(byte[] value){return Base64.getEncoder().encodeToString(value);}
    static void validate(JSONObject data)throws Exception {
        if(data.length()!=3||!data.has("preferences")||!data.has("cookies")||!data.has("extra"))throw new IOException("Invalid settings schema.");
        JSONObject p=data.getJSONObject("preferences");if(p.length()!=6)throw new IOException("Invalid preferences.");
        for(String name:INTS)if(!(p.get(name) instanceof Integer))throw new IOException("Invalid setting type.");
        if(!(p.get("background") instanceof Boolean))throw new IOException("Invalid setting type.");
        new RunPolicy(p.getInt("minimum"),p.getInt("maximum"),p.getInt("startMinute"),p.getInt("endMinute"));ConcurrencyPolicy.validate(p.getInt("concurrentWebViews"));
        data.getJSONObject("extra");if(!data.isNull("cookies"))CookieJar.decode(data.getString("cookies"));
    }
    public static void apply(Context c,JSONObject data)throws Exception {apply(c,data,new CookieVault(c));}
    static void apply(Context c,JSONObject data,CookieVault vault)throws Exception {apply(c,data,vault,NO_EXTRAS);}
    private static void apply(Context c,JSONObject data,CookieVault vault,Extras extras)throws Exception {
        validate(data);if(!QueueControl.lock.compareAndSet(false,true))throw new IOException("Pause retrieval before importing settings.");
        try{applyLocked(c,data,vault,extras);}finally{QueueControl.lock.set(false);}
    }
    private static void applyLocked(Context c,JSONObject data,CookieVault vault,Extras extras)throws Exception {
        SharedPreferences prefs=SessionState.prefs(c);Map<String,?> before=new HashMap<>(prefs.getAll());String old=vault.configured()?vault.stored():null;
        try {
            if(data.isNull("cookies"))vault.remove();else vault.save(CookieJar.decode(data.getString("cookies")));
            JSONObject p=data.getJSONObject("preferences");SharedPreferences.Editor e=prefs.edit();for(String n:INTS)e.putInt(n,p.getInt(n));e.putBoolean("background",p.getBoolean("background"));
            // A backup is configuration, not authorization to resume a queue on another device.
            e.putBoolean("armed",false).putBoolean("userPaused",true).putString("cookieGeneration",UUID.randomUUID().toString()).putString("sessionEvidence","UNKNOWN").putString("state","Stopped — User Disabled").remove("refreshLegacy");
            if(!e.commit())throw new IOException("Settings could not be saved.");
            extras.apply();
            WorkScheduler.cancel(c);WorkScheduler.recovery(c);WorkScheduler.plan(c,false);
        }catch(Exception error){
            try{extras.rollback();}catch(Exception rollback){new Diagnostics(c).unexpected("SETTINGS",rollback);}
            if(old==null)vault.remove();else vault.save(CookieJar.decode(old));
            SharedPreferences.Editor e=prefs.edit().clear();for(Map.Entry<String,?> entry:before.entrySet()){Object v=entry.getValue();String n=entry.getKey();if(v instanceof Integer)e.putInt(n,(Integer)v);else if(v instanceof Long)e.putLong(n,(Long)v);else if(v instanceof Boolean)e.putBoolean(n,(Boolean)v);else if(v instanceof String)e.putString(n,(String)v);}e.commit();throw new IOException("Settings import failed; previous settings restored.");
        }
    }
    private SettingsArchive(){}
}
