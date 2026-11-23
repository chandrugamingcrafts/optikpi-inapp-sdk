package com.optikpi.inapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.view.ViewGroup;
import com.optikpi.inapp.model.PopupContent;
import com.optikpi.inapp.render.PopupOverlayView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
public class PopupOverlayViewTest {
  private final Context context = RuntimeEnvironment.getApplication();

  private static final PopupOverlayView.Callbacks NO_OP =
      new PopupOverlayView.Callbacks() {
        @Override
        public void onButton(int index) {}

        @Override
        public void onImageTap() {}

        @Override
        public void onClose() {}

        @Override
        public void onOutsideTap() {}
      };

  private PopupOverlayView view(String layout, String size) throws Exception {
    JSONObject json =
        new JSONObject()
            .put("version", 1)
            .put("layout", layout)
            .put("size", size)
            .put("title", "Hello")
            .put("body", "Body");
    PopupContent content = PopupContent.tryParse(json);
    assertNotNull(content);
    return new PopupOverlayView(context, content, null, NO_OP);
  }

  /** The card is the last child: a scrim, when the layout has one, comes before it. */
  private static ViewGroup.LayoutParams cardParams(PopupOverlayView view) {
    View card = view.getChildAt(view.getChildCount() - 1);
    return card.getLayoutParams();
  }

  @Test
  public void aModalTakesItsWidthFromItsSize() throws Exception {
    int sm = cardParams(view("modal", "sm")).width;
    int md = cardParams(view("modal", "md")).width;
    int lg = cardParams(view("modal", "lg")).width;
    assertTrue("sm < md", sm < md);
    assertTrue("md < lg", md < lg);
  }

  @Test
  public void aFixedRatioModalKeepsItsAspectRatio() throws Exception {
    ViewGroup.LayoutParams params = cardParams(view("modal", "iab_300x250"));
    assertEquals(300.0 / 250.0, (double) params.width / params.height, 0.02);
  }

  @Test
  public void aBottomSheetTakesItsHeightFromItsSize() throws Exception {
    int sm = cardParams(view("bottomSheet", "sm")).height;
    int md = cardParams(view("bottomSheet", "md")).height;
    int lg = cardParams(view("bottomSheet", "lg")).height;
    assertTrue("sm < md", sm < md);
    assertTrue("md < lg", md < lg);
  }

  @Test
  public void aBannerOfTheSmallSizeIsNarrowerThanTheScreen() throws Exception {
    int md = cardParams(view("banner", "md")).width;
    int iab = cardParams(view("banner", "iab_320x50")).width;
    assertTrue(iab <= md);
    ViewGroup.LayoutParams params = cardParams(view("banner", "iab_320x50"));
    assertEquals(320.0 / 50.0, (double) params.width / params.height, 0.1);
  }

  @Test
  public void blockingLayoutsTakeEveryTouch() throws Exception {
    for (String layout : new String[] {"modal", "fullscreen", "bottomSheet"}) {
      assertTrue(layout + " takes touches", view(layout, "lg").isClickable());
    }
  }

  @Test
  public void aBannerOrToastLetsTouchesReachThePage() throws Exception {
    PopupOverlayView banner = view("banner", "md");
    PopupOverlayView toast = view("toast", "md");
    assertFalse("banner overlay must not swallow touches", banner.isClickable());
    assertFalse("toast overlay must not swallow touches", toast.isClickable());
    // Only the card takes touches.
    assertTrue(banner.getChildAt(banner.getChildCount() - 1).isClickable());
    assertTrue(toast.getChildAt(toast.getChildCount() - 1).isClickable());
    // And they have no full-screen scrim in front of the page.
    assertEquals(1, banner.getChildCount());
    assertEquals(1, toast.getChildCount());
  }

  // WindowInsets.Builder and the Type constants only exist from API 30.
  private static WindowInsets bars(int left, int top, int right, int bottom) {
    return new WindowInsets.Builder()
        .setInsets(WindowInsets.Type.systemBars(), android.graphics.Insets.of(left, top, right, bottom))
        .build();
  }

  @Test
  @Config(sdk = 34)
  public void aBannerAndAToastStayClearOfTheSystemBars() throws Exception {
    for (String layout : new String[] {"banner", "toast"}) {
      PopupOverlayView view = view(layout, "md");
      FrameLayout.LayoutParams before = (FrameLayout.LayoutParams) cardParams(view);
      view.dispatchApplyWindowInsets(bars(10, 48, 20, 32));
      FrameLayout.LayoutParams after = (FrameLayout.LayoutParams) cardParams(view);
      assertEquals(layout + " left", before.leftMargin + 10, after.leftMargin);
      assertEquals(layout + " top", before.topMargin + 48, after.topMargin);
      assertEquals(layout + " right", before.rightMargin + 20, after.rightMargin);
      assertEquals(layout + " bottom", before.bottomMargin + 32, after.bottomMargin);
    }
  }

  @Test
  @Config(sdk = 34)
  public void aFullscreenPopupKeepsItsContentClearOfTheSystemBars() throws Exception {
    PopupOverlayView view = view("fullscreen", "md");
    view.dispatchApplyWindowInsets(bars(0, 48, 0, 32));
    View card = view.getChildAt(view.getChildCount() - 1);
    assertEquals(48, card.getPaddingTop());
    assertEquals(32, card.getPaddingBottom());
  }

  @Test
  @Config(sdk = 34)
  public void aBottomSheetKeepsItsContentAboveTheNavigationBar() throws Exception {
    PopupOverlayView view = view("bottomSheet", "md");
    int before = cardParams(view).height;
    view.dispatchApplyWindowInsets(bars(0, 0, 0, 32));
    View sheet = view.getChildAt(view.getChildCount() - 1);
    assertEquals(32, sheet.getPaddingBottom());
    // The usable height shrank by the inset, and the sheet grew back by it to run under the bar.
    assertTrue(sheet.getLayoutParams().height >= before - 32);
  }
}
