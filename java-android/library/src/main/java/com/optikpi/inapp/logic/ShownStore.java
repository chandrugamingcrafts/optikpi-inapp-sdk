package com.optikpi.inapp.logic;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pop-up ids already drawn on this device, so a refetch never shows one again. Kept across logins:
 * the server only ever returns a pop-up to the player it was sent to.
 *
 * <p>An id is kept for as long as the server could still return that pop-up. {@code API.md} lets
 * the server keep returning a pop-up that already has an impression, so an id with no {@code
 * expiresAt} is kept for good: dropping it (as a count cap would) lets the pop-up be picked again.
 * An id with an {@code expiresAt} goes once that time passes, because {@code ShowRules} refuses an
 * expired pop-up anyway; that is the bound on the store's size. The id outlives the expiry by
 * {@link #EXPIRY_GRACE_MS}: expiry is judged by the device clock, which can be set wrong for a
 * while, and the record is deleted for good.
 *
 * <p>Stored as an ordered JSON array. An entry is a plain string (no expiry; also the format older
 * versions wrote) or {@code {"id": ..., "exp": epochMillis}}.
 */
public final class ShownStore {
  public static final String KEY = "optikpi_inapp.shown";

  /** How long past its expiry an id is kept, to ride out a device clock that is set far ahead. */
  public static final long EXPIRY_GRACE_MS = 30L * 24 * 60 * 60 * 1000;

  private final SharedPreferences prefs;
  private final LongSupplier clock;

  /** id to the epoch millis it expires at, or {@link #NEVER}. Insertion-ordered, oldest first. */
  private Map<String, Long> entries;

  private static final long NEVER = 0L;

  public ShownStore(Context context) {
    this(context, System::currentTimeMillis);
  }

  /** For tests, which need to move the clock. */
  public ShownStore(Context context, LongSupplier clock) {
    prefs =
        context.getApplicationContext().getSharedPreferences("optikpi_inapp", Context.MODE_PRIVATE);
    this.clock = clock;
  }

  private Map<String, Long> entries() {
    if (entries == null) {
      Map<String, Long> read = new LinkedHashMap<>();
      try {
        String raw = prefs.getString(KEY, null);
        if (raw != null) {
          JSONArray array = new JSONArray(raw);
          for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (item instanceof String) {
              read.put((String) item, NEVER);
            } else if (item instanceof JSONObject) {
              String id = ((JSONObject) item).optString("id", null);
              if (id != null) {
                read.put(id, ((JSONObject) item).optLong("exp", NEVER));
              }
            }
          }
        }
      } catch (ClassCastException | JSONException e) {
        // An unreadable value (another format) starts the list over.
        read.clear();
      }
      entries = read;
    }
    return entries;
  }

  /** Drops what expired more than the grace period ago; true when something went. */
  private boolean prune(Map<String, Long> map) {
    long now = clock.getAsLong();
    return map.values().removeIf(exp -> exp != NEVER && exp + EXPIRY_GRACE_MS <= now);
  }

  /** Ids still worth remembering, oldest first. */
  public synchronized Set<String> load() {
    Map<String, Long> map = entries();
    if (prune(map)) {
      persist(map);
    }
    return new LinkedHashSet<>(map.keySet());
  }

  /**
   * Remembers {@code id}, drawn now. {@code expiresAt} is when the pop-up stops being valid, or
   * null when it never does.
   */
  public synchronized void markShown(String id, Instant expiresAt) {
    Map<String, Long> map = entries();
    boolean pruned = prune(map);
    if (map.containsKey(id)) {
      if (pruned) {
        persist(map);
      }
      return;
    }
    map.put(id, expiresAt == null ? NEVER : Math.max(1L, expiresAt.toEpochMilli()));
    persist(map);
  }

  private void persist(Map<String, Long> map) {
    JSONArray array = new JSONArray();
    try {
      for (Map.Entry<String, Long> entry : map.entrySet()) {
        if (entry.getValue() == NEVER) {
          array.put(entry.getKey());
        } else {
          array.put(new JSONObject().put("id", entry.getKey()).put("exp", entry.getValue()));
        }
      }
    } catch (JSONException e) {
      return;
    }
    prefs.edit().putString(KEY, array.toString()).apply();
  }
}
