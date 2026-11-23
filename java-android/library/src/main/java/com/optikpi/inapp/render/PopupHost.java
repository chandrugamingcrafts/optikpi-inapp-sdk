package com.optikpi.inapp.render;

import android.app.Activity;
import android.content.Context;
import android.view.inputmethod.InputMethodManager;
import java.lang.ref.WeakReference;
import androidx.activity.ComponentActivity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import androidx.activity.OnBackPressedCallback;
import com.optikpi.inapp.ActivePopup;
import com.optikpi.inapp.OptikpiInAppController;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class PopupHost {
  private static final WeakHashMap<Activity, HostBinding> BINDINGS = new WeakHashMap<>();

  private PopupHost() {}

  public static boolean isAttached(Activity activity) {
    return BINDINGS.containsKey(activity);
  }

  public static void attach(Activity activity, OptikpiInAppController controller) {
    detach(activity);
    // The window's decor, not android.R.id.content: an ActionBar is a sibling of the content, and
    // a blocking pop-up has to cover its Up and menu controls too.
    View decor = activity.getWindow().getDecorView();
    if (!(decor instanceof ViewGroup root)) {
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
    HostBinding binding = new HostBinding(controller, root, overlay, back);
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
    // Work already queued for this binding must not run against a root it has left.
    binding.detached = true;
    binding.handler.removeCallbacksAndMessages(null);
    binding.restorePage();
    ViewGroup parent = (ViewGroup) binding.overlay.getParent();
    if (parent != null) {
      parent.removeView(binding.overlay);
    }
    binding.back.remove();
  }

  private static final class HostBinding {
    final OptikpiInAppController controller;
    final ViewGroup root;
    final FrameLayout overlay;
    final OnBackPressedCallback back;
    final OptikpiInAppController.CurrentListener listener;

    final Handler handler = new Handler(Looper.getMainLooper());
    volatile boolean detached;

    /** The page's views and what their accessibility importance was before a blocking pop-up. */
    private final Map<View, Integer> hidden = new HashMap<>();

    private final Map<ViewGroup, Integer> focusability = new HashMap<>();
    private WeakReference<View> focusBefore = new WeakReference<>(null);

    /**
     * A blocking pop-up covers the page, so neither a screen reader nor a keyboard (or the IME)
     * may reach what is behind it: hide it from accessibility, take focus off it and keep focus
     * from going back until the pop-up closes.
     */
    void hidePage() {
      if (hidden.isEmpty()) {
        focusBefore = new WeakReference<>(root.findFocus());
      }
      for (int i = 0; i < root.getChildCount(); i++) {
        View child = root.getChildAt(i);
        if (child == overlay || hidden.containsKey(child)) {
          continue;
        }
        hidden.put(child, child.getImportantForAccessibility());
        child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (child instanceof ViewGroup) {
          ViewGroup group = (ViewGroup) child;
          focusability.put(group, group.getDescendantFocusability());
          group.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        child.clearFocus();
      }
      InputMethodManager imm =
          (InputMethodManager) overlay.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
      if (imm != null) {
        imm.hideSoftInputFromWindow(overlay.getWindowToken(), 0);
      }
    }

    /**
     * An impression is a pop-up the player could see: wait for a frame that is really about to be
     * drawn, and only count it if this overlay is still attached and its window visible. A post
     * would also run for an activity that stopped a moment after the pop-up was added.
     */
    private void reportWhenDrawn(PopupOverlayView view, ActivePopup active) {
      ViewTreeObserver observer = view.getViewTreeObserver();
      observer.addOnPreDrawListener(
          new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
              ViewTreeObserver current = view.getViewTreeObserver();
              if (current.isAlive()) {
                current.removeOnPreDrawListener(this);
              }
              if (!detached
                  && view.getParent() == overlay
                  && view.isAttachedToWindow()
                  && view.isShown()
                  && view.getWindowVisibility() == View.VISIBLE) {
                controller.didDraw(active);
              }
              return true;
            }
          });
    }

    void restorePage() {
      for (Map.Entry<View, Integer> entry : hidden.entrySet()) {
        entry.getKey().setImportantForAccessibility(entry.getValue());
      }
      boolean wasHidden = !hidden.isEmpty();
      hidden.clear();
      for (Map.Entry<ViewGroup, Integer> entry : focusability.entrySet()) {
        entry.getKey().setDescendantFocusability(entry.getValue());
      }
      focusability.clear();
      View before = focusBefore.get();
      focusBefore = new WeakReference<>(null);
      // Still part of this page (not removed while the pop-up was up)?
      if (wasHidden && before != null && before.getRootView() == root.getRootView()) {
        before.requestFocus();
      }
    }

    HostBinding(
        OptikpiInAppController controller,
        ViewGroup root,
        FrameLayout overlay,
        OnBackPressedCallback back) {
      this.controller = controller;
      this.root = root;
      this.overlay = overlay;
      this.back = back;
      this.listener =
          active -> {
            handler.post(
                () -> {
                  if (detached) {
                    return;
                  }
                  overlay.removeAllViews();
                  boolean blocks =
                      active != null && PopupBlocksPage.blocksPage(active.getContent());
                  back.setEnabled(blocks);
                  // Banners and toasts leave the page usable, so it stays exposed for them.
                  if (blocks) {
                    hidePage();
                  } else {
                    restorePage();
                  }
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
                  if (blocks) {
                    view.requestFocus(); // keyboard focus belongs to the pop-up now
                  }
                  reportWhenDrawn(view, active);
                });
          };
    }
  }
}