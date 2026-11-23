package com.optikpi.inapp.render;

import android.graphics.Bitmap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small least-recently-used cache of decoded images. Each pop-up is shown once per device, so a
 * big cache would rarely be hit again; the bound keeps a long-lived process from holding every
 * campaign image it ever drew (the Flutter package keeps eight, so does this).
 */
public final class BoundedBitmapCache {
  public static final int DEFAULT_MAX_ENTRIES = 8;

  private final int maxEntries;
  private final LinkedHashMap<String, Bitmap> entries =
      new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
          return size() > BoundedBitmapCache.this.maxEntries;
        }
      };

  public BoundedBitmapCache() {
    this(DEFAULT_MAX_ENTRIES);
  }

  public BoundedBitmapCache(int maxEntries) {
    this.maxEntries = Math.max(1, maxEntries);
  }

  public synchronized Bitmap get(String url) {
    return entries.get(url);
  }

  public synchronized void put(String url, Bitmap bitmap) {
    entries.put(url, bitmap);
  }

  public synchronized int size() {
    return entries.size();
  }
}
