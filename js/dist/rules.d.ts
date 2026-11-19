import type { InAppPopup } from "./parse.js";
export declare function isRouteBlocked(route: string | null, blockedRoutes: string[]): boolean;
export declare function pickPopupToShow(args: {
    waiting: InAppPopup[];
    shownIds: Set<string>;
    route: string | null;
    blockedRoutes: string[];
    now: Date;
}): InAppPopup | null;
export declare class FetchThrottle {
    static readonly defaultIntervalMs = 300000;
    static readonly minIntervalSec = 30;
    static readonly maxIntervalSec: number;
    private lastResponseAt;
    intervalMs: number;
    canFetch(now: Date): boolean;
    recordResponse(at: Date, nextCheckAfterSec?: number): void;
}
