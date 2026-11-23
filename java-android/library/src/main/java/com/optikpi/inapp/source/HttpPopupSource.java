package com.optikpi.inapp.source;

import com.optikpi.inapp.InAppLog;
import com.optikpi.inapp.Version;
import com.optikpi.inapp.events.PopupEvent;
import com.optikpi.inapp.model.PopupFetchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public final class HttpPopupSource extends PopupSource {
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private final OkHttpClient client;

  public HttpPopupSource() {
    client =
        new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
  }

  public HttpPopupSource(OkHttpClient client) {
    this.client = client;
  }

  private static String base(PopupRequestContext context) {
    String base = context.baseUrl;
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
  }

  private Request.Builder headers(PopupRequestContext context) {
    return new Request.Builder()
        .header("authorization", "Bearer " + context.apiKey)
        .header("x-player-token", context.playerToken)
        .header("x-sdk-version", "optikpi_inapp-java-android/" + Version.OPTIKPI_INAPP_VERSION)
        .header("accept", "application/json");
  }

  @Override
  public PopupFetchResult fetch(PopupRequestContext context) throws Exception {
    String path = "/v1/inapp/popups";
    Request request =
        headers(context).url(base(context) + path).get().build();
    try (Response response = client.newCall(request).execute()) {
      if (response.code() != 200) {
        throw new PopupHttpException(response.code(), path);
      }
      String body = response.body() != null ? response.body().string() : "{}";
      return PopupFetchResult.fromJson(new JSONObject(body));
    }
  }

  @Override
  public void sendEvents(PopupRequestContext context, List<PopupEvent> events) throws Exception {
    String path = "/ingest/popup";
    JSONObject root = new JSONObject();
    JSONArray array = new JSONArray();
    for (PopupEvent event : events) {
      array.put(event.toJson());
    }
    root.put("events", array);
    Request request =
        headers(context)
            .url(base(context) + path)
            .header("content-type", "application/json")
            .post(RequestBody.create(root.toString(), JSON))
            .build();
    try (Response response = client.newCall(request).execute()) {
      int code = response.code();
      if (code < 200 || code >= 300) {
        throw new PopupHttpException(code, path);
      }
      InAppLog.log("sent " + events.size() + " event(s)");
    }
  }
}