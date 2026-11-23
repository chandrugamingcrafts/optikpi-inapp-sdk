package com.optikpi.inapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import com.optikpi.inapp.model.PopupContent;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class HandlersTest {
  private final OptikpiInAppController controller =
      OptikpiInAppController.getInstance(RuntimeEnvironment.getApplication());

  @After
  public void clear() {
    controller.setHandlers(null, null);
    shadowOf(Looper.getMainLooper()).idle();
  }

  private static PopupContent withDeepLink() throws Exception {
    return PopupContent.tryParse(
        new JSONObject()
            .put("version", 1)
            .put("layout", "modal")
            .put("size", "md")
            .put("title", "Hello")
            .put(
                "buttons",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("label", "Go")
                            .put("style", "primary")
                            .put(
                                "action",
                                new JSONObject()
                                    .put("type", "deeplink")
                                    .put("value", "app://cashier")))));
  }

  private void tapFirstButton() throws Exception {
    controller.showTest(withDeepLink());
    shadowOf(Looper.getMainLooper()).idle();
    ActivePopup active = controller.getCurrent();
    assertNotNull(active);
    controller.tapButton(active, 0);
    shadowOf(Looper.getMainLooper()).idle();
  }

  @Test
  public void aReboundHandlerIsTheOneCalled() throws Exception {
    List<String> first = new ArrayList<>();
    List<String> second = new ArrayList<>();
    controller.setHandlers(first::add, null);
    controller.setHandlers(second::add, null);
    shadowOf(Looper.getMainLooper()).idle();

    tapFirstButton();

    assertEquals(List.of(), first);
    assertEquals(List.of("app://cashier"), second);
  }

  @Test
  public void aClearedHandlerIsNotCalled() throws Exception {
    List<String> calls = new ArrayList<>();
    controller.setHandlers(calls::add, null);
    controller.setHandlers(null, null);
    shadowOf(Looper.getMainLooper()).idle();

    tapFirstButton();

    assertEquals(List.of(), calls);
  }
}
