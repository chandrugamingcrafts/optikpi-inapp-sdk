export type PopupLayout =
  | "modal"
  | "bottomSheet"
  | "banner"
  | "toast"
  | "imageOnly";

export type PopupSize = "sm" | "md" | "lg" | "iab_300x250" | "iab_320x50";
export type PopupPosition = "top" | "bottom" | "center" | "topRight" | "bottomRight";
export type PopupActionType = "deeplink" | "url" | "dismiss" | "custom";
export type PopupButtonStyle = "primary" | "secondary";
export type PopupClosePosition = "topRight" | "topLeft";

export type PopupAction = {
  type: PopupActionType;
  value?: string;
  customData?: Record<string, string>;
};

export type PopupButton = {
  label: string;
  style: PopupButtonStyle;
  action: PopupAction;
};

export type PopupImage = {
  url: string;
  alt?: string;
  tapAction?: PopupAction;
};

export type PopupClose = {
  show: boolean;
  position: PopupClosePosition;
  tapOutsideToClose: boolean;
};

export type PopupTheme = {
  background: string;
  text: string;
  primary: string;
  overlayOpacity: number;
  radius: number;
};

export type PopupContent = {
  version: 1;
  layout: PopupLayout;
  size: PopupSize;
  position?: PopupPosition;
  image?: PopupImage;
  title?: string;
  body?: string;
  buttons: PopupButton[];
  close: PopupClose;
  theme: PopupTheme;
  autoDismissSec?: number;
};

export type InAppPopup = {
  id: string;
  trackingId: string;
  campaignId?: string;
  createdAt: Date;
  expiresAt?: Date;
  content: PopupContent;
};

export type PopupFetchResult = {
  popups: InAppPopup[];
  nextCheckAfterSec?: number;
};

type LayoutSpec = {
  sizes: PopupSize[];
  positions?: PopupPosition[];
  hasText: boolean;
  maxButtons: number;
  autoDismiss: boolean;
};

const LAYOUTS: Record<PopupLayout, LayoutSpec> = {
  modal: {
    sizes: ["sm", "md", "lg", "iab_300x250"],
    hasText: true,
    maxButtons: 2,
    autoDismiss: false,
  },
  bottomSheet: {
    sizes: ["sm", "md", "lg"],
    hasText: true,
    maxButtons: 2,
    autoDismiss: false,
  },
  banner: {
    sizes: ["md", "iab_320x50"],
    positions: ["top", "bottom"],
    hasText: true,
    maxButtons: 1,
    autoDismiss: true,
  },
  toast: {
    sizes: ["sm", "md"],
    positions: ["topRight", "bottomRight"],
    hasText: true,
    maxButtons: 1,
    autoDismiss: true,
  },
  imageOnly: {
    sizes: ["md", "lg", "iab_300x250"],
    hasText: false,
    maxButtons: 0,
    autoDismiss: false,
  },
};

const LAYOUT_NAMES = Object.keys(LAYOUTS) as PopupLayout[];
const SIZE_NAMES: PopupSize[] = ["sm", "md", "lg", "iab_300x250", "iab_320x50"];
const POSITION_NAMES: PopupPosition[] = [
  "top",
  "bottom",
  "center",
  "topRight",
  "bottomRight",
];
const ACTION_TYPES: PopupActionType[] = ["deeplink", "url", "dismiss", "custom"];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asEnum<T extends string>(value: unknown, allowed: readonly T[]): T | null {
  return typeof value === "string" && (allowed as readonly string[]).includes(value)
    ? (value as T)
    : null;
}

function parseColor(value: unknown, fallback: string): string {
  if (typeof value !== "string") return fallback;
  const hex = value.trim();
  if (!/^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$/.test(hex)) {
    return fallback;
  }
  return hex;
}

function parseCustomData(json: unknown): Record<string, string> {
  const data: Record<string, string> = {};
  if (!Array.isArray(json)) return data;
  for (const entry of json) {
    if (!isRecord(entry)) continue;
    const key = typeof entry.key === "string" ? entry.key.trim() : "";
    if (!key || typeof entry.value !== "string") continue;
    data[key] = entry.value;
  }
  return data;
}

function parseAction(json: unknown): PopupAction | null {
  if (!isRecord(json)) return null;
  const type = asEnum(json.type, ACTION_TYPES);
  if (!type) return null;
  if (type === "custom") {
    const customData = parseCustomData(json.customData);
    if (Object.keys(customData).length === 0) return { type: "dismiss" };
    return { type, customData };
  }
  const value = typeof json.value === "string" ? json.value.trim() : "";
  if (type !== "dismiss" && !value) return { type: "dismiss" };
  return { type, value: type === "dismiss" ? undefined : value };
}

function parseButton(json: unknown): PopupButton | null {
  if (!isRecord(json)) return null;
  const action = parseAction(json.action);
  if (!action) return null;
  return {
    label: typeof json.label === "string" ? json.label : "",
    style: json.style === "secondary" ? "secondary" : "primary",
    action,
  };
}

function parseImage(json: unknown): PopupImage | undefined {
  if (!isRecord(json)) return undefined;
  const url = typeof json.url === "string" ? json.url.trim() : "";
  if (!url) return undefined;
  return {
    url,
    alt: typeof json.alt === "string" ? json.alt : undefined,
    tapAction: parseAction(json.tapAction) ?? undefined,
  };
}

export function isGradientUrl(url: string): boolean {
  return url.startsWith("gradient:");
}

export function gradientStops(url: string): [string, string] {
  const parts = url.slice("gradient:".length).split(",");
  const from = parseColor(parts[0], "#98A2B3");
  const to = parseColor(parts[1], from);
  return [from, to];
}

export function isDarkBackground(background: string): boolean {
  let hex = background.replace("#", "");
  if (hex.length === 3) {
    hex = hex
      .split("")
      .map((c) => c + c)
      .join("");
  }
  if (hex.length === 8) hex = hex.slice(0, 6);
  if (hex.length !== 6) return false;
  const r = parseInt(hex.slice(0, 2), 16);
  const g = parseInt(hex.slice(2, 4), 16);
  const b = parseInt(hex.slice(4, 6), 16);
  return r * 0.299 + g * 0.587 + b * 0.114 < 140;
}

export function parsePopupContent(json: unknown): PopupContent | null {
  if (!isRecord(json)) return null;
  if (json.version != null && json.version !== 1) return null;
  const layout = asEnum(json.layout, LAYOUT_NAMES);
  if (!layout) return null;
  const spec = LAYOUTS[layout];
  const size = asEnum(json.size, SIZE_NAMES);
  const position = asEnum(json.position, POSITION_NAMES);
  const buttons = (Array.isArray(json.buttons) ? json.buttons : [])
    .map(parseButton)
    .filter((button): button is PopupButton => button != null)
    .slice(0, spec.maxButtons);
  const autoDismiss = json.autoDismissSec;
  const close = isRecord(json.close) ? json.close : {};
  const theme = isRecord(json.theme) ? json.theme : {};
  const opacity = theme.overlayOpacity;
  const radius = theme.radius;

  return {
    version: 1,
    layout,
    size: size && spec.sizes.includes(size) ? size : spec.sizes[0],
    position: spec.positions
      ? position && spec.positions.includes(position)
        ? position
        : spec.positions[0]
      : undefined,
    image: parseImage(json.image),
    title: spec.hasText && typeof json.title === "string" ? json.title : undefined,
    body: spec.hasText && typeof json.body === "string" ? json.body : undefined,
    buttons,
    close: {
      show: typeof close.show === "boolean" ? close.show : true,
      position: close.position === "topLeft" ? "topLeft" : "topRight",
      tapOutsideToClose:
        typeof close.tapOutsideToClose === "boolean" ? close.tapOutsideToClose : true,
    },
    theme: {
      background: parseColor(theme.background, "#FFFFFF"),
      text: parseColor(theme.text, "#101828"),
      primary: parseColor(theme.primary, "#7F56D9"),
      overlayOpacity:
        typeof opacity === "number" && Number.isFinite(opacity)
          ? Math.min(1, Math.max(0, opacity))
          : 0.5,
      radius:
        typeof radius === "number" && Number.isFinite(radius) && radius >= 0 ? radius : 16,
    },
    autoDismissSec:
      !spec.autoDismiss
        ? undefined
        : typeof autoDismiss === "number" && Number.isFinite(autoDismiss) && autoDismiss > 0
          ? Math.ceil(autoDismiss)
          : undefined,
  };
}

function parseDate(value: unknown): Date | undefined {
  if (typeof value !== "string") return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

export function parseInAppPopup(json: unknown): InAppPopup | null {
  if (!isRecord(json)) return null;
  if (json.kind != null && json.kind !== "popup") return null;
  if (typeof json.id !== "string" || !json.id) return null;
  if (typeof json.trackingId !== "string" || !json.trackingId) return null;
  const content = parsePopupContent(json.content);
  if (!content) return null;
  return {
    id: json.id,
    trackingId: json.trackingId,
    campaignId: typeof json.campaignId === "string" ? json.campaignId : undefined,
    createdAt: parseDate(json.createdAt) ?? new Date(0),
    expiresAt: parseDate(json.expiresAt),
    content,
  };
}

export function parseFetchResult(json: unknown): PopupFetchResult {
  const map = isRecord(json) ? json : {};
  const list = Array.isArray(map.popups) ? map.popups : [];
  const next = map.nextCheckAfterSec;
  return {
    popups: list
      .map(parseInAppPopup)
      .filter((popup): popup is InAppPopup => popup != null),
    nextCheckAfterSec:
      typeof next === "number" && Number.isFinite(next) ? Math.round(next) : undefined,
  };
}

export function popupBlocksPage(layout: PopupLayout): boolean {
  return layout !== "banner" && layout !== "toast";
}
