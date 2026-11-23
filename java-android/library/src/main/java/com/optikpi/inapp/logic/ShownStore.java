package com.optikpi.inapp.logic;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * Pop-up ids already drawn on this device, so a refetch never shows one again. Kept across logins:
 * the server only ever returns a pop-up to the player it was sent to.
 *
 * <p>The ids are stored as an ordered JSON array (a {@code StringSet} has no order), so when the cap
 * is passed the oldest ids go first and a just-shown id is never the one dropped.
 */
public final class ShownStore {
  public static final String KEY = "optikpi_inapp.shown";
  public static final int MAX_IDS = 500;

  private final SharedPreferences prefs;
  private Set<String> cache;

  public ShownStore(Context context) {
    prefs =
        context.getApplicationContext().getSharedPreferences("optikpi_inapp", Context.MODE_PRIVATE);
  }

  /** Insertion-ordered, oldest first. */
  public synchronized Set<String> load() {
    if (cache != null) {
      return cache;
    }
    Set<String> ids = new LinkedHashSet<>();
    try {
      String raw = prefs.getString(KEY, null);
      if (raw != null) {
        JSONArray array = new JSONArray(raw);
        for (int i = 0; i < array.length(); i++) {
          String id = array.optString(i, null);
          if (id != null) {
            ids.add(id);
          }
        }
      }
    } catch (ClassCastException | JSONException e) {
      // An unreadable value (another format) starts the list over.
      ids.clear();
    }
    cache = ids;
    return cache;
  }

  public synchronized void markShown(String id) {
    Set<String> ids = load();
    if (!ids.add(id)) {
      return;
    }
    if (ids.size() > MAX_IDS) {
      int drop = ids.size() - MAX_IDS;
      Iterator<String> oldest = ids.iterator();
      for (int i = 0; i < drop && oldest.hasNext(); i++) {
        oldest.next();
        oldest.remove();
      }
    }
    prefs.edit().putString(KEY, new JSONArray(new ArrayList<>(ids)).toString()).apply();
  }
}
