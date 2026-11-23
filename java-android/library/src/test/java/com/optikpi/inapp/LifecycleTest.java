package com.optikpi.inapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import androidx.activity.ComponentActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import org.robolectric.android.controller.ActivityController;
import android.view.View;
import android.view.ViewGroup;
import com.optikpi.inapp.integration.OptikpiLifecycleCallbacks;
import com.optikpi.inapp.model.PopupContent;
import com.optikpi.inapp.render.PopupHost;
import com.optikpi.inapp.render.PopupOverlayView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class LifecycleTest {
  private static PopupContent modal() throws Exception {
    return PopupContent.tryParse(
        new JSONObject()
            .put("version", 1)
            .put("layout", "modal")
            .put("size", "md")
            .put("title", "Hello"));
  }

  /** The window's original content: the host's overlay is added after it. */
  private static View pageRoot(Activity activity) {
    return ((ViewGroup) activity.getWindow().getDecorView()).getChildAt(0);
  }

  private static int overlays(Activity activity) {
    ViewGroup content = (ViewGroup) activity.getWindow().getDecorView();
    int count = 0;
    for (int i = 0; i < content.getChildCount(); i++) {
      View host = content.getChildAt(i);
      if (host instanceof ViewGroup) {
        ViewGroup group = (ViewGroup) host;
        for (int j = 0; j < group.getChildCount(); j++) {
          if (group.getChildAt(j) instanceof PopupOverlayView) {
            count++;
          }
        }
      }
    }
    return count;
  }

  @Test
  public void anActivityThatStartedBeforeInitGetsTheOverlay() throws Exception {
    // init runs after login, when the activity is long since started: the callbacks never saw it.
    Activity activity =
        Robolectric.buildActivity(ComponentActivity.class).create().start().resume().get();
    OptikpiInAppController controller = OptikpiInAppController.getInstance(activity);
    OptikpiLifecycleCallbacks callbacks = new OptikpiLifecycleCallbacks(controller);

    assertFalse(PopupHost.isAttached(activity));
    callbacks.adoptRunningActivity(activity);
    assertTrue(PopupHost.isAttached(activity));

    controller.showTest(modal());
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, overlays(activity));

    // Adopting again is harmless: still one overlay host, not two.
    callbacks.adoptRunningActivity(activity);
    assertTrue(PopupHost.isAttached(activity));
    PopupHost.detach(activity);
  }

  @Test
  public void aStopWithoutAStartDoesNotBreakForegroundTracking() {
    Activity activity = Robolectric.buildActivity(Activity.class).create().get();
    OptikpiInAppController controller =
        OptikpiInAppController.getInstance(RuntimeEnvironment.getApplication());
    OptikpiLifecycleCallbacks callbacks = new OptikpiLifecycleCallbacks(controller);
    // A stop that was never preceded by a start used to leave the counter negative.
    callbacks.onActivityStopped(activity);
    callbacks.onActivityStarted(activity);
    assertNotNull(activity);
    assertTrue(PopupHost.isAttached(activity));
    callbacks.onActivityStopped(activity);
    assertFalse(PopupHost.isAttached(activity));
  }

  @Test
  public void anActivityThatIsAlreadyStoppedIsNotAdopted() {
    // A login that finishes after the user left the app: stopped, but not finishing or destroyed.
    ActivityController<ComponentActivity> controllerOf =
        Robolectric.buildActivity(ComponentActivity.class).create().start().resume();
    ComponentActivity activity = controllerOf.get();
    controllerOf.pause().stop();
    OptikpiInAppController controller = OptikpiInAppController.getInstance(activity);
    OptikpiLifecycleCallbacks callbacks = new OptikpiLifecycleCallbacks(controller);

    callbacks.adoptRunningActivity(activity);

    assertFalse(PopupHost.isAttached(activity));
  }

  @Test
  public void aBlockingPopupHidesThePageFromScreenReadersAndABannerDoesNot() throws Exception {
    ComponentActivity activity =
        Robolectric.buildActivity(ComponentActivity.class).create().start().resume().get();
    View page = new View(activity);
    activity.setContentView(page);
    OptikpiInAppController controller = OptikpiInAppController.getInstance(activity);
    new OptikpiLifecycleCallbacks(controller).adoptRunningActivity(activity);
    int original = pageRoot(activity).getImportantForAccessibility();

    controller.showTest(modal());
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, pageRoot(activity).getImportantForAccessibility());

    controller.showTest(
        PopupContent.tryParse(
            new JSONObject()
                .put("version", 1)
                .put("layout", "banner")
                .put("size", "md")
                .put("title", "Hi")));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(original, pageRoot(activity).getImportantForAccessibility());
    PopupHost.detach(activity);
  }

  private static ComponentActivity activityAt(String url) {
    return Robolectric.buildActivity(
            ComponentActivity.class, new Intent().setData(Uri.parse(url)))
        .create()
        .start()
        .resume()
        .get();
  }

  @Test
  public void anActivityBackOnTopGetsItsOwnRouteBack() {
    ComponentActivity first = activityAt("app://first");
    ComponentActivity second = activityAt("app://second");
    OptikpiInAppController controller = OptikpiInAppController.getInstance(first);
    OptikpiLifecycleCallbacks callbacks = new OptikpiLifecycleCallbacks(controller);

    callbacks.onActivityStarted(first);
    callbacks.onActivityResumed(first);
    assertEquals("app://first", controller.getRoute());
    // A dialog-themed activity opens over it: the first stays started, and is not started again.
    callbacks.onActivityStarted(second);
    callbacks.onActivityResumed(second);
    assertEquals("app://second", controller.getRoute());
    callbacks.onActivityPaused(second);
    callbacks.onActivityStopped(second);
    callbacks.onActivityResumed(first);

    assertEquals("app://first", controller.getRoute());
  }

  @Test
  public void aRouteTheAppReportedIsTheOneRestored() {
    ComponentActivity first = activityAt("app://first");
    ComponentActivity second = activityAt("app://second");
    OptikpiInAppController controller = OptikpiInAppController.getInstance(first);
    OptikpiLifecycleCallbacks callbacks = new OptikpiLifecycleCallbacks(controller);

    callbacks.onActivityStarted(first);
    callbacks.onActivityResumed(first);
    callbacks.routeReported(null, "/dashboard/home"); // the app's own path for the top activity
    controller.didChangeRoute("/dashboard/home");
    callbacks.onActivityStarted(second);
    callbacks.onActivityResumed(second);
    callbacks.onActivityStopped(second);
    callbacks.onActivityResumed(first);

    assertEquals("/dashboard/home", controller.getRoute());
  }

  @Test
  public void workQueuedBeforeDetachDoesNotHideThePageAfterwards() throws Exception {
    ComponentActivity activity =
        Robolectric.buildActivity(ComponentActivity.class).create().start().resume().get();
    View page = new View(activity);
    activity.setContentView(page);
    OptikpiInAppController controller = OptikpiInAppController.getInstance(activity);
    int original = pageRoot(activity).getImportantForAccessibility();
    PopupHost.attach(activity, controller);

    controller.showTest(modal()); // queues the host's work on the main looper
    PopupHost.detach(activity); // the activity stops before it runs
    shadowOf(Looper.getMainLooper()).idle();

    assertEquals(original, pageRoot(activity).getImportantForAccessibility());
  }
}
