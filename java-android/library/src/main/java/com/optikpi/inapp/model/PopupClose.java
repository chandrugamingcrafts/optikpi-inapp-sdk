package com.optikpi.inapp.model;

import static com.optikpi.inapp.model.JsonHelpers.map;
import static com.optikpi.inapp.model.PopupEnums.PopupClosePosition;

import org.json.JSONObject;

public final class PopupClose {
  public final boolean show;
  public final PopupClosePosition position;
  public final boolean tapOutsideToClose;

  public PopupClose(boolean show, PopupClosePosition position, boolean tapOutsideToClose) {
    this.show = show;
    this.position = position;
    this.tapOutsideToClose = tapOutsideToClose;
  }

  public static PopupClose parse(Object json) {
    JSONObject map = map(json);
    if (map == null) {
      return new PopupClose(true, PopupClosePosition.topRight, true);
    }
    boolean show = map.optBoolean("show", true);
    PopupClosePosition position =
        PopupEnums.enumByName(PopupClosePosition.class, map.opt("position"));
    if (position == null) {
      position = PopupClosePosition.topRight;
    }
    boolean tapOutside = map.has("tapOutsideToClose") ? map.optBoolean("tapOutsideToClose") : true;
    return new PopupClose(show, position, tapOutside);
  }
}