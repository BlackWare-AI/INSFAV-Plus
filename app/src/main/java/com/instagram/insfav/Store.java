package com.instagram.insfav;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Keeps the original collection document independent from replaceable image metadata. */
final class Store {
    private static Store instance;
    static synchronized Store get(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
        return instance;
    }
    final File root;
    JSONObject index, settings;
    private LinkedHashSet<String> cachedUsers;
    private int total;
    private final File diagnostic;
    Store(Context context) {
        root = context.getFilesDir(); diagnostic = new File(root, "diagnostics.txt");
        index = readObject("thumbnail-index.json"); settings = readObject("image-settings.json");
        // Migrate only obsolete retrieval state, never the original collection document.
        for (String name : new String[]{"queue.json", "network.json", "profiles.json"}) new AtomicFile(new File(root, name)).delete();
        if ("scanning".equals(settings.optString("scanState"))) {
            try {settings.put("scanState", "interrupted"); saveSettings();} catch (Exception ignored) { }
        }
    }
    static String normalize(String value) {return ThumbnailPolicy.normalize(value);}
    static String key(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return out.toString();
        } catch (Exception error) {throw new IllegalStateException(error);}
    }
    synchronized String original() {
        try {return new String(new AtomicFile(new File(root, "document.json")).readFully(), StandardCharsets.UTF_8);}
        catch (FileNotFoundException error) {return "";}
        catch (IOException error) {log("document_read", "IO_ERROR"); return "";}
    }
    static JSONArray collections(String raw) throws JSONException {
        Object root = new JSONTokener(raw).nextValue();
        Object value = root instanceof JSONArray ? root : ((JSONObject)root).get("favorite_page_pref");
        if (value instanceof String) value = new JSONTokener((String)value).nextValue();
        if (!(value instanceof JSONArray)) throw new JSONException("Invalid collection array");
        JSONArray collections = (JSONArray)value;
        for (int i = 0; i < collections.length(); i++) {
            JSONObject collection = collections.getJSONObject(i);
            if (!(collection.get("name") instanceof String)) throw new JSONException("Invalid collection name");
            JSONArray items = collection.getJSONArray("items");
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.getJSONObject(j);
                if (!(item.get("pageId") instanceof String) || item.has("name") && !(item.get("name") instanceof String)) throw new JSONException("Invalid account fields");
            }
        }
        return collections;
    }
    synchronized void importDocument(String raw) throws Exception {
        collections(raw); write("document.json", raw); cachedUsers = null; log("import", "success");
    }
    synchronized Set<String> users() throws JSONException {
        if (cachedUsers == null) {
            LinkedHashSet<String> next = new LinkedHashSet<>(); int count = 0; String raw = original();
            if (!raw.isEmpty()) {
                JSONArray array = collections(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONArray items = array.getJSONObject(i).getJSONArray("items"); count += items.length();
                    for (int j = 0; j < items.length(); j++) {
                        String user = normalize(items.getJSONObject(j).getString("pageId")); if (!user.isEmpty()) next.add(user);
                    }
                }
            }
            cachedUsers = next; total = count;
        }
        return Collections.unmodifiableSet(cachedUsers);
    }
    /** Fresh validation only: never use a stale cached user set for destructive cleanup. */
    synchronized Set<String> cleanupUsers() {
        try {
            String raw = original(); if (raw.isEmpty()) return null;
            JSONTokener rootToken=new JSONTokener(raw);rootToken.nextValue();if(rootToken.nextClean()!=0)return null;
            Object root=new JSONTokener(raw).nextValue();if(root instanceof JSONObject){Object nested=((JSONObject)root).get("favorite_page_pref");if(nested instanceof String){JSONTokener token=new JSONTokener((String)nested);token.nextValue();if(token.nextClean()!=0)return null;}}
            JSONArray data = collections(raw); Set<String> users = new HashSet<>();
            for (int i=0;i<data.length();i++) {
                JSONArray items=data.getJSONObject(i).getJSONArray("items");
                for(int n=0;n<items.length();n++) {
                    String user=normalize(items.getJSONObject(n).getString("pageId"));
                    if(!user.matches("[a-z0-9._]{1,30}"))return null;
                    users.add(user);
                }
            }
            return users.isEmpty()?null:users;
        } catch(Exception error) {log("thumbnail_cleanup", "INVALID_FAVORITES_PRESERVED");return null;}
    }
    synchronized int total() throws JSONException {if (cachedUsers == null) users(); return total;}
    synchronized void clearDocument() {new AtomicFile(new File(root, "document.json")).delete(); cachedUsers = null; log("clear_collections", "success");}
    synchronized JSONObject readObject(String name) {
        try {return new JSONObject(new String(new AtomicFile(new File(root, name)).readFully(), StandardCharsets.UTF_8));}
        catch (Exception error) {return new JSONObject();}
    }
    synchronized void write(String name, String value) throws IOException {
        AtomicFile file = new AtomicFile(new File(root, name)); FileOutputStream output = null;
        try {output = file.startWrite(); output.write(value.getBytes(StandardCharsets.UTF_8)); file.finishWrite(output);}
        catch (IOException error) {if (output != null) file.failWrite(output); throw error;}
    }
    synchronized void saveSettings() throws IOException {write("image-settings.json", settings.toString());}
    synchronized void replaceIndex(JSONObject next) throws IOException {write("thumbnail-index.json", next.toString()); index = next;}
    synchronized void log(String event, String code) {
        try {
            if (diagnostic.length() > 256 * 1024) {File old = new File(root, "diagnostics.1.txt"); old.delete(); diagnostic.renameTo(old);}
            try (FileOutputStream output = new FileOutputStream(diagnostic, true)) {
                String clean = event.replaceAll("[^a-zA-Z0-9_:-]", "") + " " + code.replaceAll("[^a-zA-Z0-9_:-]", "");
                output.write((System.currentTimeMillis() + " " + clean + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { }
    }
    synchronized String logs() {
        StringBuilder out = new StringBuilder("INSFAV diagnostics — no account identifiers or folder URIs\n");
        for (String name : new String[]{"diagnostics.1.txt", "diagnostics.txt"}) {
            try {out.append(new String(java.nio.file.Files.readAllBytes(new File(root, name).toPath()), StandardCharsets.UTF_8));}
            catch (IOException ignored) { }
        }
        return out.toString();
    }
}
