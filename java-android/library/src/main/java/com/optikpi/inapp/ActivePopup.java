package com.optikpi.inapp;

import com.optikpi.inapp.model.InAppPopup;
import com.optikpi.inapp.model.PopupContent;
import com.optikpi.inapp.source.PopupRequestContext;

public final class ActivePopup {
  public final InAppPopup popup;
  public final PopupRequestContext context;
  public final DeepLinkHandler onDeepLink;
  public final CustomActionHandler onCustomAction;
  public final boolean isTest;

  /** Set when the first frame is reported, so a second attached activity doesn't report it again. */
  boolean drawn;

  ActivePopup(
      InAppPopup popup,
      PopupRequestContext context,
      DeepLinkHandler onDeepLink,
      CustomActionHandler onCustomAction,
      boolean isTest) {
    this.popup = popup;
    this.context = context;
    this.onDeepLink = onDeepLink;
    this.onCustomAction = onCustomAction;
    this.isTest = isTest;
  }

  public PopupContent getContent() {
    return popup.content;
  }

  public interface DeepLinkHandler {
    void onDeepLink(String link);
  }

  public interface CustomActionHandler {
    void onCustomAction(java.util.Map<String, String> data);
  }
}