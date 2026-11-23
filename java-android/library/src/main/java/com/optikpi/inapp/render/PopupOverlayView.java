package com.optikpi.inapp.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.GradientDrawable.Orientation;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.optikpi.inapp.model.PopupButton;
import com.optikpi.inapp.model.PopupClose;
import com.optikpi.inapp.model.PopupContent;
import com.optikpi.inapp.model.PopupEnums.PopupButtonStyle;
import com.optikpi.inapp.model.PopupEnums.PopupClosePosition;
import com.optikpi.inapp.model.PopupEnums.PopupLayout;
import com.optikpi.inapp.model.PopupEnums.PopupPosition;
import com.optikpi.inapp.model.PopupImage;
import com.optikpi.inapp.model.PopupTheme;

public final class PopupOverlayView extends FrameLayout {
  public interface Callbacks {
    void onButton(int index);

    void onImageTap();

    void onClose();

    void onOutsideTap();
  }

  private final PopupContent content;
  private final Callbacks callbacks;
  private final PopupImageLoader images;

  public PopupOverlayView(
      Context context, PopupContent content, PopupImageLoader images, Callbacks callbacks) {
    super(context);
    this.content = content;
    this.images = images;
    this.callbacks = callbacks;
    setClickable(true);
    setFocusable(true);
    build();
  }

  private int dp(int value) {
    return Math.round(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
  }

  private void build() {
    removeAllViews();
    switch (content.layout) {
      case modal -> buildModal();
      case fullscreen -> buildFullscreen();
      case bottomSheet -> buildBottomSheet();
      case banner -> buildBanner();
      case toast -> buildToast();
      case imageOnly -> buildImageOnly();
    }
  }

  private void addOverlayScrim() {
    View scrim = new View(getContext());
    scrim.setBackgroundColor(applyAlpha(0xFF000000, content.theme.overlayOpacity));
    if (content.close.tapOutsideToClose) {
      scrim.setOnClickListener(v -> callbacks.onOutsideTap());
    }
    addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
  }

  private int applyAlpha(int color, double opacity) {
    int alpha = (int) (255 * opacity);
    return (alpha << 24) | (color & 0x00FFFFFF);
  }

  private void buildModal() {
    addOverlayScrim();
    FrameLayout card = cardContainer(dp(328), ViewGroup.LayoutParams.WRAP_CONTENT);
    LayoutParams params = new LayoutParams(dp(328), ViewGroup.LayoutParams.WRAP_CONTENT);
    params.gravity = Gravity.CENTER;
    addView(card, params);
  }

  private void buildFullscreen() {
    addOverlayScrim();
    FrameLayout card = cardContainer(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
  }

  private void buildBottomSheet() {
    addOverlayScrim();
    FrameLayout card = cardContainer(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    LayoutParams params =
        new LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.gravity = Gravity.BOTTOM;
    addView(card, params);
  }

  private void buildBanner() {
    FrameLayout card = cardContainer(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    LayoutParams params =
        new LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.gravity = content.position == PopupPosition.bottom ? Gravity.BOTTOM : Gravity.TOP;
    params.setMargins(dp(12), dp(12), dp(12), dp(12));
    addView(card, params);
  }

  private void buildToast() {
    FrameLayout card = cardContainer(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT);
    LayoutParams params =
        new LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT);
    params.gravity =
        content.position == PopupPosition.bottomRight
            ? (Gravity.BOTTOM | Gravity.END)
            : (Gravity.TOP | Gravity.END);
    params.setMargins(dp(16), dp(16), dp(16), dp(16));
    addView(card, params);
  }

  private void buildImageOnly() {
    addOverlayScrim();
    FrameLayout card = new FrameLayout(getContext());
    styleCard(card, true);
    View image = imageView(true);
    card.addView(image, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(360)));
    addCloseButton(card, true);
    LayoutParams params = new LayoutParams(dp(328), ViewGroup.LayoutParams.WRAP_CONTENT);
    params.gravity = Gravity.CENTER;
    addView(card, params);
  }

  private FrameLayout cardContainer(int width, int height) {
    FrameLayout card = new FrameLayout(getContext());
    styleCard(card, PopupBlocksPage.blocksPage(content));
    LinearLayout column = new LinearLayout(getContext());
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(20), dp(20), dp(20), dp(20));
    if (content.image != null) {
      int imageHeight = content.layout == PopupLayout.banner ? dp(48) : dp(160);
      column.addView(
          imageView(content.layout == PopupLayout.imageOnly || content.image.tapAction != null),
          new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, imageHeight));
    }
    if (hasText()) {
      column.addView(textBlock(), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    if (!content.buttons.isEmpty()) {
      column.addView(buttonRow(), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    ScrollView scroll = new ScrollView(getContext());
    scroll.addView(column);
    card.addView(scroll, new FrameLayout.LayoutParams(width, height));
    addCloseButton(card, PopupBlocksPage.blocksPage(content));
    return card;
  }

  private void styleCard(FrameLayout card, boolean blocking) {
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(content.theme.background);
    bg.setCornerRadius(dp((int) Math.round(content.theme.radius)));
    card.setBackground(bg);
    card.setElevation(blocking ? dp(8) : dp(4));
  }

  private boolean hasText() {
    return (content.title != null && !content.title.isEmpty())
        || (content.body != null && !content.body.isEmpty());
  }

  private View textBlock() {
    LinearLayout column = new LinearLayout(getContext());
    column.setOrientation(LinearLayout.VERTICAL);
    if (content.title != null && !content.title.isEmpty()) {
      TextView title = new TextView(getContext());
      title.setText(content.title);
      title.setTextColor(content.theme.text);
      title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
      title.setTypeface(title.getTypeface(), Typeface.BOLD);
      column.addView(title);
    }
    if (content.body != null && !content.body.isEmpty()) {
      TextView body = new TextView(getContext());
      body.setText(content.body);
      body.setTextColor(content.theme.text);
      body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
      body.setPadding(0, dp(6), 0, 0);
      column.addView(body);
    }
    return column;
  }

  private View buttonRow() {
    LinearLayout row = new LinearLayout(getContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(0, dp(16), 0, 0);
    for (int i = 0; i < content.buttons.size(); i++) {
      PopupButton button = content.buttons.get(i);
      Button view = new Button(getContext());
      view.setAllCaps(false);
      view.setText(button.label);
      view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
      int index = i;
      view.setOnClickListener(v -> callbacks.onButton(index));
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
      if (i > 0) {
        lp.setMarginStart(dp(8));
      }
      styleButton(view, button.style);
      row.addView(view, lp);
    }
    return row;
  }

  private void styleButton(Button view, PopupButtonStyle style) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setCornerRadius(dp(8));
    if (style == PopupButtonStyle.primary) {
      drawable.setColor(content.theme.primary);
      view.setTextColor(0xFFFFFFFF);
    } else {
      drawable.setStroke(dp(1), content.theme.primary);
      drawable.setColor(content.theme.background);
      view.setTextColor(content.theme.primary);
    }
    view.setBackground(drawable);
    view.setPadding(dp(12), dp(10), dp(12), dp(10));
  }

  private View imageView(boolean tappable) {
    PopupImage image = content.image;
    if (image == null) {
      return new View(getContext());
    }
    ImageView view = new ImageView(getContext());
    view.setScaleType(ImageView.ScaleType.CENTER_CROP);
    if (image.isGradient()) {
      int[] colors = image.gradientColors();
      GradientDrawable gradient =
          new GradientDrawable(Orientation.TOP_BOTTOM, colors);
      view.setBackground(gradient);
    } else {
      Bitmap bitmap = images != null ? images.getCached(image.url) : null;
      if (bitmap != null) {
        view.setImageBitmap(bitmap);
      } else if (images != null) {
        images.load(image.url);
      }
    }
    if (tappable) {
      view.setOnClickListener(v -> callbacks.onImageTap());
    }
    view.setContentDescription(image.alt);
    return view;
  }

  private void addCloseButton(FrameLayout card, boolean blocking) {
    PopupClose close = content.close;
    if (!close.show) {
      return;
    }
    TextView button = new TextView(getContext());
    button.setText("✕");
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    button.setGravity(Gravity.CENTER);
    button.setTextColor(content.theme.isDarkBackground() ? 0xFFFFFFFF : 0xFF374151);
    GradientDrawable bg = new GradientDrawable();
    bg.setShape(GradientDrawable.OVAL);
    bg.setColor(content.theme.isDarkBackground() ? 0x66000000 : 0xCCFFFFFF);
    button.setBackground(bg);
    button.setOnClickListener(v -> callbacks.onClose());
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(40), dp(40));
    lp.gravity =
        close.position == PopupClosePosition.topLeft
            ? (Gravity.TOP | Gravity.START)
            : (Gravity.TOP | Gravity.END);
    card.addView(button, lp);
  }
}