import { type InitOptions } from "./controller";
import type { PopupContent } from "./parse";
export type { InitOptions, PopupCustomActionHandler, PopupDeepLinkHandler } from "./controller";
export { parsePopupContent } from "./parse";
export type { PopupContent } from "./parse";
export { OPTIKPI_INAPP_JS_VERSION } from "./version";
export declare const OptikpiInApp: {
    debug: boolean;
    readonly controller: import("./controller").OptikpiInAppController;
    init(options: InitOptions): Promise<void>;
    setPlayerToken(playerToken: string): void;
    logout(): Promise<void>;
    didChangeRoute(route: string): void;
    showTest(content: PopupContent): void;
    onForeground(): void;
};
