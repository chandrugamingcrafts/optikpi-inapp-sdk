package com.optikpi.inapp;

import android.app.Application;
import android.content.Context;
import com.optikpi.inapp.integration.OptikpiLifecycleCallbacks;
import com.optikpi.inapp.model.PopupContent;
import com.optikpi.inapp.source.MockPopupSource;
import com.optikpi.inapp.source.PopupSource;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** OptiKPI in-app pop-ups for Java Android. See README.md for the five-step integration. */
public final class OptikpiInApp {
  private static boolean lifecycleRegistered;

  private OptikpiInApp() {}

  public static boolean isDebug() {
    return InAppLog.isDebug();
  }

  public static void setDebug(boolean value) {
    InAppLog.setDebug(value);
  }

  public static OptikpiInAppController controller(Context context) {
    return OptikpiInAppController.getInstance(context);
  }

  public static void init(
      Context context,
      String apiKey,
      String playerToken,
      String baseUrl,
      ActivePopup.DeepLinkHandler onDeepLink,
      ActivePopup.CustomActionHandler onCustomAction,
      List<String> blockedRoutes,
      PopupSource source) {
    Context app = context.getApplicationContext();
    OptikpiInAppController ctrl = OptikpiInAppController.getInstance(app);
    PopupSource resolved = source != null ? source : ctrl.defaultHttpSource();
    ensureLifecycle(app, ctrl);
    ctrl.initWithToken(
        apiKey,
        playerToken,
        baseUrl,
        resolved,
        onDeepLink,
        onCustomAction,
        blockedRoutes != null ? blockedRoutes : Collections.emptyList());
  }

  public static void init(Context context, String apiKey, String playerToken, String baseUrl) {
    init(context, apiKey, playerToken, baseUrl, null, null, Collections.emptyList(), null);
  }

  public static void setPlayerToken(Context context, String playerToken) {
    OptikpiInAppController.getInstance(context).setPlayerToken(playerToken);
  }

  public static void logout(Context context) {
    OptikpiInAppController.getInstance(context).logout();
  }

  public static void didChangeRoute(Context context, String route) {
    OptikpiInAppController.getInstance(context).didChangeRoute(route);
  }

  public static void showTest(Context context, PopupContent content) {
    OptikpiInAppController.getInstance(context).showTest(content);
  }

  public static PopupSource mockSource(Context context) {
    return new MockPopupSource(context);
  }

  public static PopupSource mockSource(Context context, Map<String, String> playerData) {
    return new MockPopupSource(context, playerData);
  }

  private static void ensureLifecycle(Context app, OptikpiInAppController controller) {
    if (lifecycleRegistered || !(app instanceof Application application)) {
      return;
    }
    application.registerActivityLifecycleCallbacks(new OptikpiLifecycleCallbacks(controller));
    lifecycleRegistered = true;
  }
}