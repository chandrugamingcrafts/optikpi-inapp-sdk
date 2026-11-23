package com.optikpi.inapp.events;

import android.content.Context;
import android.content.SharedPreferences;
import com.optikpi.inapp.InAppLog;
import com.optikpi.inapp.source.PopupHttpException;
import com.optikpi.inapp.source.PopupRequestContext;
import com.optikpi.inapp.source.PopupSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

public final class EventQueue {
  public static final String QUEUE_KEY = "optikpi_inapp.events";
  public static final String TRACKED_KEY = "optikpi_inapp.tracked";
  public static final int MAX_QUEUED = 500;
  public static final int MAX_TRACKED = 2000;

  private final SharedPreferences prefs;
  private final int batchSize;
  private volatile boolean flushing;
  private PopupSource pendingSource;
  private PopupRequestContext pendingContext;

  public EventQueue(Context context, int batchSize) {
    prefs = context.getApplicationContext().getSharedPreferences("optikpi_inapp", Context.MODE_PRIVATE);
    this.batchSize = batchSize;
  }

  public synchronized boolean enqueue(PopupEvent event, String workspaceId, String playerId) {
    Set<String> tracked = new HashSet<>(prefs.getStringSet(TRACKED_KEY, new HashSet<>()));
    if (tracked.contains(event.getDedupeKey())) {
      return false;
    }
    List<String> queue = new ArrayList<>(prefs.getStringSet(QUEUE_KEY, new HashSet<>()));
    JSONObject item = new JSONObject();
    try {
      item.put("workspaceId", workspaceId);
      item.put("playerId", playerId);
      item.put("event", event.toJson());
    } catch (Exception e) {
      return false;
    }
    queue.add(item.toString());
    tracked.add(event.getDedupeKey());
    if (queue.size() > MAX_QUEUED) {
      queue = new ArrayList<>(queue.subList(queue.size() - MAX_QUEUED, queue.size()));
    }
  List<String> trackedList = new ArrayList<>(tracked);
    if (trackedList.size() > MAX_TRACKED) {
      trackedList = new ArrayList<>(trackedList.subList(trackedList.size() - MAX_TRACKED, trackedList.size()));
      tracked = new HashSet<>(trackedList);
    }
    prefs.edit().putStringSet(QUEUE_KEY, new HashSet<>(queue)).putStringSet(TRACKED_KEY, tracked).apply();
    InAppLog.log("queued " + event.type.name() + " " + event.trackingId);
    return true;
  }

  public synchronized void flush(PopupSource source, PopupRequestContext context) throws Exception {
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
    Set<String> rawItems = prefs.getStringSet(QUEUE_KEY, new HashSet<>());
    List<QueuedItem> items = new ArrayList<>();
    for (String raw : rawItems) {
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
        InAppLog.log("event send failed, will retry: " + e);
        return;
      }
      Set<String> sent = new HashSet<>();
      for (QueuedItem q : batch) {
        sent.add(q.raw);
      }
      Set<String> remaining = new HashSet<>(prefs.getStringSet(QUEUE_KEY, new HashSet<>()));
      remaining.removeAll(sent);
      prefs.edit().putStringSet(QUEUE_KEY, remaining).apply();
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
        String workspaceId = json.optString("workspaceId", null);
        String playerId = json.optString("playerId", null);
        if (event == null || workspaceId == null || playerId == null) {
          return null;
        }
        return new QueuedItem(raw, workspaceId, playerId, event);
      } catch (Exception e) {
        return null;
      }
    }
  }
}