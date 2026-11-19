import type { PopupEvent } from "./http.js";
export declare function loadShownIds(): Set<string>;
export declare function markShown(id: string): void;
export type QueuedEvent = {
    raw: string;
    workspaceId: string;
    playerId: string;
    event: PopupEvent;
};
export declare function enqueueEvent(event: PopupEvent, workspaceId: string, playerId: string): boolean;
export declare function queuedFor(workspaceId: string, playerId: string): QueuedEvent[];
export declare function removeQueued(raws: string[]): void;
