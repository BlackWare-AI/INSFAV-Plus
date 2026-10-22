package com.instagram.insfav;

import org.json.*;
import java.util.Iterator;

/** Informational target only. A non-256 image stays usable and is never rewritten. */
final class ResolutionAudit {
    static JSONObject result(int count,int unknown,boolean complete)throws JSONException {
        return new JSONObject().put("expected","256×256").put("count",count).put("unknown",unknown)
            .put("complete",complete).put("scope",complete?"ALL_SCANNED_IMAGES":"SAVED_PROFILES");
    }
    static JSONObject snapshot(JSONObject index)throws JSONException {
        JSONObject saved=index.optJSONObject("resolutionAudit");
        if(saved!=null && saved.optBoolean("complete") && "256×256".equals(saved.optString("expected")))return new JSONObject(saved.toString());
        // Existing installations can show known dimensions immediately, without opening a file.
        int count=0,unknown=0;JSONObject profiles=index.optJSONObject("profiles");
        if(profiles!=null)for(Iterator<String> it=profiles.keys();it.hasNext();) {
            JSONObject image=profiles.optJSONObject(it.next());int w=image==null?0:image.optInt("width"),h=image==null?0:image.optInt("height");
            if(w<=0||h<=0)unknown++;else if(w!=256||h!=256)count++;
        }
        return result(count,unknown,false);
    }
    private ResolutionAudit(){}
}
