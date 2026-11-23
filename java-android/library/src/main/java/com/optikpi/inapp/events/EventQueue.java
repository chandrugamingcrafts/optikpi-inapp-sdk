package com.optikpi.inapp.events;

import android.content.Context;
import android.content.SharedPreferences;
import com.optikpi.inapp.InAppLog;
import com.optikpi.inapp.source.PopupHttpException;
import com.optikpi.inapp.source.PopupRequestContext;
import com.optikpi.inapp.source.PopupSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Events wait here, in SharedPreferences, until the server takes them. Each {@code (trackingId,
 * type)} gets in once, so it is sent once, however often the app reports it.
 *
 * <p>Both lists are stored as ordered JSON arrays (a {@code StringSet} has no order), so when a cap
 * is passed the oldest entries go first, as {@code API.md} requires.
 */
public final class EventQueue {
  public static final String QUEUE_KEY = "optikpi_inapp.events";
  public static final String TRACKED_KEY = "optikpi_inapp.tracked";

  /** Oldest events drop off first when the device has been offline for a very long time. */
  public static final int MAX_QUEUED = 500;

  public static final int MAX_TRACKED = 2000;

  private final SharedPreferences prefs;
  private final int batchSize;
  private boolean flushing;
  private PopupSource pendingSource;
  private PopupRequestContext pendingContext;

  public EventQueue(Context context, int batchSize) {
    prefs =
        context.getApplicationContext().getSharedPreferences("optikpi_inapp", Context.MODE_PRIVATE);
    this.batchSize = batchSize;
  }

  private List<String> readList(String key) {
    List<String> out = new ArrayList<>();
    try {
      String raw = prefs.getString(key, null);
      if (raw == null) {
        return out;
      }
      JSONArray array = new JSONArray(raw);
      for (int i = 0; i < array.length(); i++) {
        String item = array.optString(i, null);
        if (item != null) {
          out.add(item);
        }
      }
    } catch (ClassCastException | JSONException e) {
      // An unreadable value (another format) starts the list over.
      out.clear();
    }
    return out;
  }

  private static String encode(List<String> list) {
    return new JSONArray(list).toString();
  }

  /** False when this {@code (trackingId, type)} was queued before. */
  public synchronized boolean enqueue(PopupEvent event, String workspaceId, String playerId) {
    Set<String> tracked = new LinkedHashSet<>(readList(TRACKED_KEY));
    if (tracked.contains(event.getDedupeKey())) {
      return false;
    }
    JSONObject item = new JSONObject();
    try {
      item.put("workspaceId", workspaceId);
      item.put("playerId", playerId);
      item.put("event", event.toJson());
    } catch (JSONException e) {
      return false;
    }
    List<String> queue = readList(QUEUE_KEY);
    queue.add(item.toString());
    if (queue.size() > MAX_QUEUED) {
      queue = new ArrayList<>(queue.subList(queue.size() - MAX_QUEUED, queue.size()));
    }
    tracked.add(event.getDedupeKey());
    if (tracked.size() > MAX_TRACKED) {
      int drop = tracked.size() - MAX_TRACKED;
      Iterator<String> oldest = tracked.iterator();
      for (int i = 0; i < drop && oldest.hasNext(); i++) {
        oldest.next();
        oldest.remove();
      }
    }
    prefs
        .edit()
        .putString(QUEUE_KEY, encode(queue))
        .putString(TRACKED_KEY, encode(new ArrayList<>(tracked)))
        .apply();
    InAppLog.log("queued " + event.type.name() + " " + event.trackingId);
    return true;
  }

  public synchronized int length() {
    return readList(QUEUE_KEY).size();
  }

  /**
   * Sends what is queued for {@code context}'s player, in batches: a player token speaks for one
   * player, so another player's events wait for that player's next session. A network error or a
   * refused batch stops the flush and the rest waits for the next call. A batch refused for its
   * token or key throws the {@link PopupHttpException}, from the last pass only, so the caller can
   * pause.
   */
  public synchronized void flush(PopupSource source, PopupRequestContext context)
      throws Exception {
    if (flushing) {
      pendingSource = source;
      pendingContext = context;
      return;
    }
    flushing = true;
    try {
      PopupSource argsSource = source;
      PopupRequestContext argsContext = context;
      while (true) {
        pendingSource = null;
        pendingContext = null;
        try {
          flushOnce(argsSource, argsContext);
        } catch (PopupHttpException e) {
          if (pendingSource == null) {
            throw e;
          }
        }
        if (pendingSource == null) {
          break;
        }
        argsSource = pendingSource;
        argsContext = pendingContext;
      }
    } finally {
      flushing = false;
    }
  }

  private void flushOnce(PopupSource source, PopupRequestContext context) throws Exception {
    List<QueuedItem> items = new ArrayList<>();
    for (String raw : readList(QUEUE_KEY)) {
      QueuedItem item = QueuedItem.tryParse(raw);
      if (item != null
          && item.workspaceId.equals(context.workspaceId)
          && item.playerId.equals(context.playerId)) {
        items.add(item);
      }
    }
    for (int start = 0; start < items.size(); start += batchSize) {
      int end = Math.min(start + batchSize, items.size());
      List<QueuedItem> batch = items.subList(start, end);
      List<PopupEvent> events = new ArrayList<>();
      for (QueuedItem q : batch) {
        events.add(q.event);
      }
      try {
        source.sendEvents(context, events);
      } catch (PopupHttpException e) {
        InAppLog.log("event send refused, will retry: " + e);
        if (e.isAuthFailure()) {
          throw e;
        }
        return;
      } catch (Exception e) {
        // Offline or timed out: nothing else will get through now.
        InAppLog.log("event send failed, will retry: " + e);
        return;
      }
      Set<String> sent = new HashSet<>();
      for (QueuedItem q : batch) {
        sent.add(q.raw);
      }
      List<String> remaining = readList(QUEUE_KEY);
      remaining.removeAll(sent);
      prefs.edit().putString(QUEUE_KEY, encode(remaining)).apply();
    }
  }

  private static final class QueuedItem {
    final String raw;
    final String workspaceId;
    final String playerId;
    final PopupEvent event;

    QueuedItem(String raw, String workspaceId, String playerId, PopupEvent event) {
      this.raw = raw;
      this.workspaceId = workspaceId;
      this.playerId = playerId;
      this.event = event;
    }

    static QueuedItem tryParse(String raw) {
      try {
        JSONObject json = new JSONObject(raw);
        PopupEvent event = PopupEvent.tryParse(json.opt("event"));
        Object workspaceId = json.opt("workspaceId");
        Object playerId = json.opt("playerId");
        if (event == null || !(workspaceId instanceof String) || !(playerId instanceof String)) {
          return null;
        }
        return new QueuedItem(raw, (String) workspaceId, (String) playerId, event);
      } catch (JSONException e) {
        return null;
      }
    }
  }
}
