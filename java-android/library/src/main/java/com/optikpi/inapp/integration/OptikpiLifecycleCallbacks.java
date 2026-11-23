package com.optikpi.inapp.integration;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import com.optikpi.inapp.OptikpiInAppController;
import com.optikpi.inapp.render.PopupHost;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Follows the app's activities: draws pop-ups over each started one, reports each start as a screen
 * visit, and tells the controller when the app goes to and from the foreground.
 *
 * <p>Callbacks only see activities that start after they are registered. Register them in
 * {@code Application.onCreate} (see {@code OptikpiInApp.install}), or pass the running activity to
 * {@code OptikpiInApp.init}, which hands it to {@link #adoptRunningActivity}.
 */
public final class OptikpiLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
  private final OptikpiInAppController controller;

  /** The activities between onStart and onStop. Weak, and a set: it can't go negative. */
  private final Set<Activity> started = Collections.newSetFromMap(new WeakHashMap<>());

  public OptikpiLifecycleCallbacks(OptikpiInAppController controller) {
    this.controller = controller;
  }

  /**
   * An activity that was already started before these callbacks were registered, the normal case
   * when {@code init} runs after login: a single-activity app would otherwise get no overlay and no
   * foreground state until its next stop and start. Safe to call again for the same activity.
   */
  public synchronized void adoptRunningActivity(Activity activity) {
    if (activity.isFinishing() || activity.isDestroyed()) {
      return;
    }
    boolean wasForeground = !started.isEmpty();
    started.add(activity);
    if (!wasForeground) {
      controller.setForeground(true);
    }
    if (!PopupHost.isAttached(activity)) {
      PopupHost.attach(activity, controller);
      controller.didChangeRoute(routeOf(activity));
    }
  }

  private static String routeOf(Activity activity) {
    String data = activity.getIntent() != null ? activity.getIntent().getDataString() : null;
    return data != null ? data : "/" + activity.getClass().getSimpleName();
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

  @Override
  public synchronized void onActivityStarted(Activity activity) {
    boolean wasForeground = !started.isEmpty();
    started.add(activity);
    if (!wasForeground) {
      controller.setForeground(true);
    }
    PopupHost.attach(activity, controller);
    controller.didChangeRoute(routeOf(activity));
  }

  @Override
  public void onActivityResumed(Activity activity) {}

  @Override
  public void onActivityPaused(Activity activity) {}

  @Override
  public synchronized void onActivityStopped(Activity activity) {
    started.remove(activity);
    PopupHost.detach(activity);
    if (started.isEmpty()) {
      controller.setForeground(false);
    }
  }

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

  @Override
  public synchronized void onActivityDestroyed(Activity activity) {
    started.remove(activity);
    PopupHost.detach(activity);
  }
}
