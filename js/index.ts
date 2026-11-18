import { controller, type InitOptions } from "./controller";
import { inAppDebugLogging, setInAppDebugLogging } from "./log";
import type { PopupContent } from "./parse";

export type { InitOptions, PopupCustomActionHandler, PopupDeepLinkHandler } from "./controller";
export { parsePopupContent } from "./parse";
export type { PopupContent } from "./parse";
export { OPTIKPI_INAPP_JS_VERSION } from "./version";

export const OptikpiInApp = {
  get debug() {
    return inAppDebugLogging;
  },
  set debug(value: boolean) {
    setInAppDebugLogging(value);
  },
  get controller() {
    return controller;
  },
  init(options: InitOptions) {
    return controller.init(options);
  },
  setPlayerToken(playerToken: string) {
    controller.setPlayerToken(playerToken);
  },
  logout() {
    return controller.logout();
  },
  didChangeRoute(route: string) {
    controller.didChangeRoute(route);
  },
  showTest(content: PopupContent) {
    controller.showTest(content);
  },
  onForeground() {
    controller.onForeground();
  },
};
