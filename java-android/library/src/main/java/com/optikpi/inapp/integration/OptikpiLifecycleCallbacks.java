package com.optikpi.inapp.integration;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.optikpi.inapp.OptikpiInAppController;
import com.optikpi.inapp.render.PopupHost;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
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

  /** Where the route an activity reported rides through a recreation of that activity. */
  private static final String ROUTE_STATE_KEY = "com.optikpi.inapp.route";
  /** The activities between onStart and onStop. Weak, and a set: it can't go negative. */
  private final Set<Activity> started = Collections.newSetFromMap(new WeakHashMap<>());

  /**
   * The route each activity last reported, so an activity that comes back to the top (a dialog or
   * translucent activity closing over it never restarts it) gets its own route back, not the
   * covering activity's.
   */
  private final Map<Activity, String> routes = new WeakHashMap<>();

  private WeakReference<Activity> top = new WeakReference<>(null);

  /**
   * Whether any activity has been seen. A cleared weak reference can't tell "never seen one" from
   * "the last one was collected", and only the first activity may inherit a route the app
   * reported before the callbacks existed.
   */
  private boolean observedActivity;

  public OptikpiLifecycleCallbacks(OptikpiInAppController controller) {
    this.controller = controller;
  }

  /**
   * An activity that was already started before these callbacks were registered, the normal case
   * when {@code init} runs after login: a single-activity app would otherwise get no overlay and no
   * foreground state until its next stop and start. Safe to call again for the same activity.
   */
  public synchronized void adoptRunningActivity(Activity activity) {
    if (!isOnScreen(activity)) {
      // The app is in the background as far as we can tell: don't fetch or report until it returns.
      if (started.isEmpty()) {
        controller.setForeground(false);
      }
      return;
    }
    boolean wasForeground = !started.isEmpty();
    started.add(activity);
    if (!wasForeground) {
      controller.setForeground(true);
    }
    if (!PopupHost.isAttached(activity)) {
      PopupHost.attach(activity, controller);
      String route = currentRoute(activity);
      markTop(activity);
      controller.didChangeRoute(route);
    }
  }

  /**
   * Seeds the foreground state when {@code init} had no activity to adopt (an {@code Application}
   * context): until an activity starts there is nothing to say the app is on screen, and a process
   * woken for a background component must not fetch.
   */
  public synchronized void syncForeground(boolean processInForeground) {
    if (!observedActivity && started.isEmpty()) {
      controller.setForeground(processInForeground);
    }
  }

  /** The app reported a route: remember it for [activity], or for the top one when null. */
  public synchronized void routeReported(Activity activity, String route) {
    Activity target = activity != null ? activity : top.get();
    if (target != null && route != null) {
      routes.put(target, route);
    }
  }

  private void markTop(Activity activity) {
    top = new WeakReference<>(activity);
    observedActivity = true;
  }

  private String currentRoute(Activity activity) {
    String reported = routes.get(activity);
    if (reported == null && !observedActivity) {
      // No activity has been seen yet, so a route the controller already holds was reported by the
      // app itself (before install, or before this first adoption): keep it, don't replace it
      // with the activity's class name.
      reported = controller.getRoute();
      if (reported != null) {
        routes.put(activity, reported);
      }
    }
    return reported != null ? reported : routeOf(activity);
  }

  /**
   * Whether the activity is started and visible. A stopped activity (a login that finishes after the
   * user left the app) is neither finishing nor destroyed, but showing a pop-up on it would count an
   * impression nobody saw, and mark the app as foreground. The next {@code onActivityStarted}
   * picks it up when the user returns.
   */
  private static boolean isOnScreen(Activity activity) {
    if (activity.isFinishing() || activity.isDestroyed()) {
      return false;
    }
    if (activity instanceof LifecycleOwner) {
      return ((LifecycleOwner) activity)
          .getLifecycle()
          .getCurrentState()
          .isAtLeast(Lifecycle.State.STARTED);
    }
    // No lifecycle to ask: a stopped activity's window is hidden.
    View decor = activity.getWindow().getDecorView();
    return decor.isAttachedToWindow() && decor.getVisibility() == View.VISIBLE;
  }

  private static String routeOf(Activity activity) {
    String data = activity.getIntent() != null ? activity.getIntent().getDataString() : null;
    return data != null ? data : "/" + activity.getClass().getSimpleName();
  }

  @Override
  public synchronized void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    // A recreated activity (rotation, a configuration change) has no entry of its own: put back the
    // route its predecessor had reported, before its start is processed as a visit.
    if (savedInstanceState != null) {
      String route = savedInstanceState.getString(ROUTE_STATE_KEY);
      if (route != null) {
        routes.put(activity, route);
      }
    }
  }

  @Override
  public synchronized void onActivityStarted(Activity activity) {
    boolean wasForeground = !started.isEmpty();
    started.add(activity);
    if (!wasForeground) {
      controller.setForeground(true);
    }
    PopupHost.attach(activity, controller);
    String route = currentRoute(activity);
    markTop(activity);
    controller.didChangeRoute(route);
  }

  @Override
  public synchronized void onActivityResumed(Activity activity) {
    // A dialog-themed or translucent activity closing leaves the one under it started, so it gets
    // no onStart: put its route back here, or the covering activity's route would stay in force.
    markTop(activity);
    controller.didChangeRoute(currentRoute(activity));
  }

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
  public synchronized void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    String route = routes.get(activity);
    if (route != null) {
      outState.putString(ROUTE_STATE_KEY, route);
    }
  }

  @Override
  public synchronized void onActivityDestroyed(Activity activity) {
    started.remove(activity);
    routes.remove(activity);
    PopupHost.detach(activity);
  }
}
