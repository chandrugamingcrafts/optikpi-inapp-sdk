package com.optikpi.inapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
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

  private static int overlays(Activity activity) {
    ViewGroup content = activity.findViewById(android.R.id.content);
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
    Activity activity = Robolectric.buildActivity(Activity.class).create().start().resume().get();
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
}
