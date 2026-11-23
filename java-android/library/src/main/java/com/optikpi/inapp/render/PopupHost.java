package com.optikpi.inapp.render;

import android.app.Activity;
import androidx.activity.ComponentActivity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.activity.OnBackPressedCallback;
import com.optikpi.inapp.ActivePopup;
import com.optikpi.inapp.OptikpiInAppController;
import java.util.WeakHashMap;

public final class PopupHost {
  private static final WeakHashMap<Activity, HostBinding> BINDINGS = new WeakHashMap<>();

  private PopupHost() {}

  public static boolean isAttached(Activity activity) {
    return BINDINGS.containsKey(activity);
  }

  public static void attach(Activity activity, OptikpiInAppController controller) {
    detach(activity);
    View content = activity.findViewById(android.R.id.content);
    if (!(content instanceof ViewGroup root)) {
      return;
    }
    FrameLayout overlay = new FrameLayout(activity);
    overlay.setLayoutParams(
        new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    overlay.setClickable(false);
    root.addView(overlay);
    OnBackPressedCallback back =
        new OnBackPressedCallback(false) {
          @Override
          public void handleOnBackPressed() {
            ActivePopup active = controller.getCurrent();
            if (active != null && PopupBlocksPage.blocksPage(active.getContent())) {
              controller.close(active, true);
            }
          }
        };
    if (activity instanceof ComponentActivity componentActivity) {
      componentActivity.getOnBackPressedDispatcher().addCallback(componentActivity, back);
    }
    HostBinding binding = new HostBinding(controller, overlay, back);
    BINDINGS.put(activity, binding);
    controller.addCurrentListener(binding.listener);
    binding.listener.onChanged(controller.getCurrent());
  }

  public static void detach(Activity activity) {
    HostBinding binding = BINDINGS.remove(activity);
    if (binding == null) {
      return;
    }
    binding.controller.removeCurrentListener(binding.listener);
    ViewGroup parent = (ViewGroup) binding.overlay.getParent();
    if (parent != null) {
      parent.removeView(binding.overlay);
    }
    binding.back.remove();
  }

  private static final class HostBinding {
    final OptikpiInAppController controller;
    final FrameLayout overlay;
    final OnBackPressedCallback back;
    final OptikpiInAppController.CurrentListener listener;

    HostBinding(OptikpiInAppController controller, FrameLayout overlay, OnBackPressedCallback back) {
      this.controller = controller;
      this.overlay = overlay;
      this.back = back;
      this.listener =
          active -> {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(
                () -> {
                  overlay.removeAllViews();
                  back.setEnabled(
                      active != null && PopupBlocksPage.blocksPage(active.getContent()));
                  if (active == null) {
                    return;
                  }
                  PopupOverlayView view =
                      new PopupOverlayView(
                          overlay.getContext(),
                          active.getContent(),
                          controller.getImages(),
                          new PopupOverlayView.Callbacks() {
                            @Override
                            public void onButton(int index) {
                              controller.tapButton(active, index);
                            }

                            @Override
                            public void onImageTap() {
                              controller.tapImage(active);
                            }

                            @Override
                            public void onClose() {
                              controller.close(active, true);
                            }

                            @Override
                            public void onOutsideTap() {
                              controller.close(active, true);
                            }
                          });
                  overlay.addView(
                      view,
                      new FrameLayout.LayoutParams(
                          FrameLayout.LayoutParams.MATCH_PARENT,
                          FrameLayout.LayoutParams.MATCH_PARENT));
                  overlay.post(() -> controller.didDraw(active));
                });
          };
    }
  }
}