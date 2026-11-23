package com.optikpi.inapp.logic;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

public final class ShownStore {
  public static final String KEY = "optikpi_inapp.shown";
  public static final int MAX_IDS = 500;

  private final SharedPreferences prefs;
  private Set<String> cache;

  public ShownStore(Context context) {
    prefs = context.getApplicationContext().getSharedPreferences("optikpi_inapp", Context.MODE_PRIVATE);
  }

  public synchronized Set<String> load() {
    if (cache != null) {
      return cache;
    }
    Set<String> stored = prefs.getStringSet(KEY, null);
    if (stored == null) {
      stored = new HashSet<>();
    } else {
      stored = new HashSet<>(stored);
    }
    cache = stored;
    return cache;
  }

  public synchronized void markShown(String id) {
    Set<String> ids = load();
    if (!ids.add(id)) {
      return;
    }
    if (ids.size() > MAX_IDS) {
      int drop = ids.size() - MAX_IDS;
      var iterator = ids.iterator();
      for (int i = 0; i < drop && iterator.hasNext(); i++) {
        iterator.next();
        iterator.remove();
      }
    }
    prefs.edit().putStringSet(KEY, new HashSet<>(ids)).apply();
  }
}