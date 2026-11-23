package com.optikpi.inapp.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.optikpi.inapp.InAppLog;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public final class PopupImageLoader {
  private static final long TIMEOUT_SEC = 8;

  private final OkHttpClient client;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<String, Future<Bitmap>> inFlight = new ConcurrentHashMap<>();
  private final Map<String, Bitmap> cache = new ConcurrentHashMap<>();

  public PopupImageLoader() {
    client = new OkHttpClient.Builder().callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS).build();
  }

  public Bitmap getCached(String url) {
    return cache.get(url);
  }

  public Future<Bitmap> load(String url) {
    if (url.startsWith("gradient:")) {
      return null;
    }
    Bitmap cached = cache.get(url);
    if (cached != null) {
      return executor.submit(() -> cached);
    }
    return inFlight.computeIfAbsent(
        url,
        key ->
            executor.submit(
                () -> {
                  try {
                    Request request = new Request.Builder().url(url).get().build();
                    try (var response = client.newCall(request).execute()) {
                      if (!response.isSuccessful() || response.body() == null) {
                        return null;
                      }
                      byte[] bytes = response.body().bytes();
                      Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                      if (bitmap != null) {
                        cache.put(url, bitmap);
                      }
                      return bitmap;
                    }
                  } catch (Exception e) {
                    InAppLog.log("image load failed: " + url);
                    return null;
                  } finally {
                    inFlight.remove(url);
                  }
                }));
  }

  public Bitmap awaitLoad(String url, long timeoutMs) {
    Future<Bitmap> future = load(url);
    if (future == null) {
      return null;
    }
    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      InAppLog.log("image still loading after " + (timeoutMs / 1000) + " s: " + url);
      return null;
    }
  }
}