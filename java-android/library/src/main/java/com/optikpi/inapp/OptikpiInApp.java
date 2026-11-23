package com.optikpi.inapp;

import android.app.Activity;
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
  private static OptikpiLifecycleCallbacks lifecycle;

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

  /**
   * Registers the activity callbacks that draw pop-ups and follow screens. Call it once from {@code
   * Application.onCreate}, so every activity is seen from its first start. {@code init} calls it
   * too if you haven't, but it can only adopt the activity you pass it then.
   */
  public static synchronized void install(Application application) {
    ensureLifecycle(application, OptikpiInAppController.getInstance(application));
  }

  /**
   * Call on login, and at app start when a stored session is restored, with the {@code
   * playerToken} your server signed for this player.
   *
   * <p>Pass the current {@code Activity} as {@code context} (or call {@link #install} in {@code
   * Application.onCreate}): the overlay is attached to activities the callbacks have seen start, and
   * a running activity is only seen if it is passed here.
   */
  public static synchronized void init(
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
    if (app instanceof Application) {
      OptikpiLifecycleCallbacks callbacks = ensureLifecycle((Application) app, ctrl);
      Activity running = activityOf(context);
      if (running != null) {
        callbacks.adoptRunningActivity(running);
      }
    }
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

  private static OptikpiLifecycleCallbacks ensureLifecycle(
      Application application, OptikpiInAppController controller) {
    if (lifecycle == null) {
      lifecycle = new OptikpiLifecycleCallbacks(controller);
      application.registerActivityLifecycleCallbacks(lifecycle);
    }
    return lifecycle;
  }

  /** The activity behind {@code context}, looking through wrappers such as a themed context. */
  private static Activity activityOf(Context context) {
    Context current = context;
    while (current instanceof android.content.ContextWrapper) {
      if (current instanceof Activity) {
        return (Activity) current;
      }
      current = ((android.content.ContextWrapper) current).getBaseContext();
    }
    return null;
  }
}
