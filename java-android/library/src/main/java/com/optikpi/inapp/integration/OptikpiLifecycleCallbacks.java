package com.optikpi.inapp.integration;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import com.optikpi.inapp.OptikpiInAppController;
import com.optikpi.inapp.render.PopupHost;

public final class OptikpiLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
  private final OptikpiInAppController controller;
  private int started;

  public OptikpiLifecycleCallbacks(OptikpiInAppController controller) {
    this.controller = controller;
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

  @Override
  public void onActivityStarted(Activity activity) {
    if (started == 0) {
      controller.setForeground(true);
    }
    started++;
    PopupHost.attach(activity, controller);
    Object route = activity.getIntent() != null ? activity.getIntent().getDataString() : null;
    if (route == null) {
      route = "/" + activity.getClass().getSimpleName();
    }
    controller.didChangeRoute(route.toString());
  }

  @Override
  public void onActivityResumed(Activity activity) {}

  @Override
  public void onActivityPaused(Activity activity) {}

  @Override
  public void onActivityStopped(Activity activity) {
    started--;
    if (started == 0) {
      controller.setForeground(false);
    }
    PopupHost.detach(activity);
  }

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

  @Override
  public void onActivityDestroyed(Activity activity) {
    PopupHost.detach(activity);
  }
}