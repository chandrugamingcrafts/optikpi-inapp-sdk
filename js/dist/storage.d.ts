import type { PopupEvent } from "./http.js";
export declare function loadShownIds(): Set<string>;
export declare function markShown(id: string): void;
/**
 * - `claimed`: this call wrote the id. Give it back with `releaseShown` if the popup is not drawn.
 * - `unstored`: nothing could be written (no storage, or a quota error). Show the popup anyway;
 *   there is nothing to give back.
 * - `taken`: another tab already claimed the id.
 * - `stale`: `stillValid` was false once the lock was held, so nothing was written.
 */
export type ClaimResult = "claimed" | "unstored" | "taken" | "stale";
/**
 * Records [id] before a popup is drawn. Waiting for the lock can take a while, so
 * `stillValid` runs once the lock is held and before anything is written: a route,
 * session or tab change in the meantime must not leave an id marked shown for a
 * popup that never appears.
 */
export declare function claimShown(id: string, stillValid?: () => boolean): Promise<ClaimResult>;
/** Takes back a `claimed` id that was never drawn, so a later visit can still show it. */
export declare function releaseShown(id: string): Promise<void>;
export type QueuedEvent = {
    raw: string;
    workspaceId: string;
    playerId: string;
    event: PopupEvent;
};
export declare function enqueueEvent(event: PopupEvent, workspaceId: string, playerId: string): boolean;
export declare function queuedFor(workspaceId: string, playerId: string): QueuedEvent[];
export declare function removeQueued(raws: string[]): void;
