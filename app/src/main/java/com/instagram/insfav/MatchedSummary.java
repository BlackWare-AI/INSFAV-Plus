package com.instagram.insfav;

import org.json.JSONObject;

/** One count per catalog/collection/access generation, owned under the Store monitor. */
final class MatchedSummary {
  private JSONObject index;
  private long indexGeneration = -1, collectionGeneration = -1;
  private boolean usable;
  private int matched;
  long builds, visited;

  int count(Store store, boolean available) throws org.json.JSONException {
    if (index != store.index || indexGeneration != store.indexGeneration
        || collectionGeneration != store.collectionGeneration || usable != available) {
      int next = 0;
      JSONObject profiles = store.index.optJSONObject("profiles");
      if (available && profiles != null) for (String user : store.users()) {
        JSONObject image = profiles.optJSONObject(user);
        if (image != null && image.optBoolean("readable")) next++;
        visited++;
      }
      index = store.index;
      indexGeneration = store.indexGeneration;
      collectionGeneration = store.collectionGeneration;
      usable = available;
      matched = next;
      builds++;
    }
    return matched;
  }
}
