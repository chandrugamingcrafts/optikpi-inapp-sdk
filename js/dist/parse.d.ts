export type PopupLayout = "modal" | "bottomSheet" | "banner" | "toast" | "imageOnly";
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
export declare function isGradientUrl(url: string): boolean;
export declare function gradientStops(url: string): [string, string];
export declare function isDarkBackground(background: string): boolean;
export declare function parsePopupContent(json: unknown): PopupContent | null;
export declare function parseInAppPopup(json: unknown): InAppPopup | null;
export declare function parseFetchResult(json: unknown): PopupFetchResult;
export declare function popupBlocksPage(layout: PopupLayout): boolean;
