import { inAppLog } from "./log";
import type { PopupEvent } from "./http";

const SHOWN_KEY = "optikpi_inapp.shown";
const QUEUE_KEY = "optikpi_inapp.events";
const TRACKED_KEY = "optikpi_inapp.tracked";
const MAX_SHOWN = 500;
const MAX_QUEUED = 500;
const MAX_TRACKED = 2000;

function readList(key: string): string[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((item) => typeof item === "string") : [];
  } catch {
    return [];
  }
}

/** Best-effort: quota or private mode must not break pop-up actions. */
function writeList(key: string, value: string[]): boolean {
  if (typeof window === "undefined") return false;
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch (error) {
    inAppLog(`storage write failed (${key}): ${error}`);
    return false;
  }
}

export function loadShownIds(): Set<string> {
  return new Set(readList(SHOWN_KEY));
}

export function markShown(id: string): void {
  try {
    const ids = loadShownIds();
    if (ids.has(id)) return;
    ids.add(id);
    let list = Array.from(ids);
    if (list.length > MAX_SHOWN) list = list.slice(list.length - MAX_SHOWN);
    writeList(SHOWN_KEY, list);
  } catch (error) {
    inAppLog(`could not remember shown id: ${error}`);
  }
}

export type QueuedEvent = {
  raw: string;
  workspaceId: string;
  playerId: string;
  event: PopupEvent;
};

function parseQueued(raw: string): QueuedEvent | null {
  try {
    const json = JSON.parse(raw) as Record<string, unknown>;
    const event = json.event as PopupEvent | undefined;
    if (
      typeof json.workspaceId !== "string" ||
      typeof json.playerId !== "string" ||
      !event ||
      typeof event.trackingId !== "string" ||
      typeof event.type !== "string" ||
      typeof event.at !== "string"
    ) {
      return null;
    }
    return {
      raw,
      workspaceId: json.workspaceId,
      playerId: json.playerId,
      event,
    };
  } catch {
    return null;
  }
}

function eventDedupeKey(
  workspaceId: string,
  playerId: string,
  event: PopupEvent
): string {
  return `${workspaceId}|${playerId}|${event.trackingId}|${event.type}`;
}

export function enqueueEvent(
  event: PopupEvent,
  workspaceId: string,
  playerId: string
): boolean {
  try {
    const tracked = readList(TRACKED_KEY);
    const dedupeKey = eventDedupeKey(workspaceId, playerId, event);
    if (tracked.includes(dedupeKey)) return false;
    const queue = readList(QUEUE_KEY);
    queue.push(JSON.stringify({ workspaceId, playerId, event }));
    tracked.push(dedupeKey);
    const nextQueue =
      queue.length > MAX_QUEUED ? queue.slice(queue.length - MAX_QUEUED) : queue;
    const nextTracked =
      tracked.length > MAX_TRACKED ? tracked.slice(tracked.length - MAX_TRACKED) : tracked;
    if (!writeList(QUEUE_KEY, nextQueue) || !writeList(TRACKED_KEY, nextTracked)) {
      return false;
    }
    inAppLog(`queued ${event.type} ${event.trackingId}`);
    return true;
  } catch (error) {
    inAppLog(`could not queue ${event.type}: ${error}`);
    return false;
  }
}

export function queuedFor(workspaceId: string, playerId: string): QueuedEvent[] {
  return readList(QUEUE_KEY)
    .map(parseQueued)
    .filter((item): item is QueuedEvent => item != null)
    .filter((item) => item.workspaceId === workspaceId && item.playerId === playerId);
}

export function removeQueued(raws: string[]) {
  try {
    const sent = new Set(raws);
    writeList(QUEUE_KEY, readList(QUEUE_KEY).filter((raw) => !sent.has(raw)));
  } catch (error) {
    inAppLog(`could not trim event queue: ${error}`);
  }
}
