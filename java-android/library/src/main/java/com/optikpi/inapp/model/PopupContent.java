package com.optikpi.inapp.model;

import static com.optikpi.inapp.model.JsonHelpers.list;
import static com.optikpi.inapp.model.JsonHelpers.map;
import static com.optikpi.inapp.model.JsonHelpers.string;
import static com.optikpi.inapp.model.PopupEnums.PopupLayout;
import static com.optikpi.inapp.model.PopupEnums.PopupPosition;
import static com.optikpi.inapp.model.PopupEnums.PopupSize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PopupContent {
  public final int version;
  public final PopupLayout layout;
  public final PopupSize size;
  public final PopupPosition position;
  public final PopupImage image;
  public final String title;
  public final String body;
  public final List<PopupButton> buttons;
  public final PopupClose close;
  public final PopupTheme theme;
  public final Integer autoDismissSec;
  public final List<String> mergeTags;
  public final PopupDelivery delivery;

  public PopupContent(
      int version,
      PopupLayout layout,
      PopupSize size,
      PopupPosition position,
      PopupImage image,
      String title,
      String body,
      List<PopupButton> buttons,
      PopupClose close,
      PopupTheme theme,
      Integer autoDismissSec,
      List<String> mergeTags,
      PopupDelivery delivery) {
    this.version = version;
    this.layout = layout;
    this.size = size;
    this.position = position;
    this.image = image;
    this.title = title;
    this.body = body;
    this.buttons = buttons;
    this.close = close;
    this.theme = theme;
    this.autoDismissSec = autoDismissSec;
    this.mergeTags = mergeTags;
    this.delivery = delivery;
  }

  public PopupLayoutSpec getSpec() {
    return PopupLayoutSpec.ALL.get(layout);
  }

  public static PopupContent tryParse(Object json) {
    JSONObject map = map(json);
    if (map == null) {
      return null;
    }
    if (map.has("version")) {
      Object versionRaw = map.opt("version");
      int version = versionRaw instanceof Number n ? n.intValue() : -1;
      if (version != 1) {
        return null;
      }
    }
    PopupLayout layout = PopupEnums.enumByName(PopupLayout.class, map.opt("layout"));
    if (layout == null) {
      return null;
    }
    PopupLayoutSpec spec = PopupLayoutSpec.ALL.get(layout);
    PopupSize size = PopupEnums.enumByName(PopupSize.class, map.opt("size"));
    PopupPosition position = PopupEnums.enumByName(PopupPosition.class, map.opt("position"));

    List<PopupButton> buttons = new ArrayList<>();
    JSONArray buttonList = list(map.opt("buttons"));
    if (buttonList != null) {
      for (int i = 0; i < buttonList.length() && buttons.size() < spec.maxButtons; i++) {
        PopupButton button = PopupButton.tryParse(buttonList.opt(i));
        if (button != null) {
          buttons.add(button);
        }
      }
    }
    buttons = Collections.unmodifiableList(buttons);

    Integer autoDismissSec = null;
    if (spec.autoDismiss) {
      Object autoDismiss = map.opt("autoDismissSec");
      if (autoDismiss instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() > 0) {
        autoDismissSec = (int) Math.ceil(n.doubleValue());
      }
    }

    List<String> mergeTags = null;
    Object mergeRaw = map.opt("mergeTags");
    if (mergeRaw instanceof JSONArray arr) {
      List<String> tags = new ArrayList<>();
      for (int i = 0; i < arr.length(); i++) {
        Object item = arr.opt(i);
        if (item instanceof String s) {
          tags.add(s);
        }
      }
      mergeTags = Collections.unmodifiableList(tags);
    }

    PopupSize resolvedSize =
        size != null && spec.sizes.contains(size) ? size : spec.sizes.get(0);
    PopupPosition resolvedPosition = null;
    if (spec.positions != null) {
      if (position != null && spec.positions.contains(position)) {
        resolvedPosition = position;
      } else {
        resolvedPosition = spec.positions.get(0);
      }
    }

    return new PopupContent(
        1,
        layout,
        resolvedSize,
        resolvedPosition,
        PopupImage.tryParse(map.opt("image")),
        spec.hasText ? string(map.opt("title")) : null,
        spec.hasText ? string(map.opt("body")) : null,
        buttons,
        PopupClose.parse(map.opt("close")),
        PopupTheme.parse(map.opt("theme")),
        autoDismissSec,
        mergeTags,
        PopupDelivery.tryParse(map.opt("delivery")));
  }
}