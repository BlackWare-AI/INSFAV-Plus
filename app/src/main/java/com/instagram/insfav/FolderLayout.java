package com.instagram.insfav;
import android.content.Context;
import java.io.IOException;
import org.json.JSONObject;
/** Independent folder layout, also carried by optional settings-archive metadata. */
public final class FolderLayout {
 public static boolean valid(int n){return n==0||n>=2&&n<=5;}
 public static int choice(Context c){int n=c.getSharedPreferences("plus-ui",0).getInt("folderColumns",0);return valid(n)?n:0;}
 public static void save(Context c,int n)throws IOException{if(!valid(n))throw new IOException("Unsupported folder layout");if(!c.getSharedPreferences("plus-ui",0).edit().putInt("folderColumns",n).commit())throw new IOException("Folder layout could not be saved");}
 public static JSONObject append(Context c,JSONObject extra)throws Exception{return extra.put("folderColumns",choice(c));}
 public static void validate(JSONObject extra)throws Exception{if(!extra.has("folderColumns"))return;Object n=extra.get("folderColumns");if(!(n instanceof Integer||n instanceof Long)||((Number)n).longValue()!=((Number)n).intValue()||!valid(((Number)n).intValue()))throw new IOException("Invalid folder layout");}
 public static void restore(Context c,JSONObject extra)throws Exception{validate(extra);if(extra.has("folderColumns"))save(c,extra.getInt("folderColumns"));}
 private FolderLayout(){}
}
