import { inAppLog } from "./log.js";
import type { PopupEvent } from "./http.js";

/** One shown popup id. The suffix is the id, so two tabs never share a list. */
const SHOWN_PREFIX = "optikpi_inapp.s.";
/** A temporary pre-draw claim. It becomes a shown record only after `didDraw`. */
const SHOWN_CLAIM_PREFIX = "optikpi_inapp.c.";
/** Earlier builds stored shown ids as one JSON list. Read once, then drop. */
const LEGACY_SHOWN_KEY = "optikpi_inapp.shown";
/** One pending event. The suffix is the dedupe key, so two tabs never share a list. */
const PENDING_PREFIX = "optikpi_inapp.q.";
/** One dedupe key. Kept after the event is sent so it is queued only once. */
const SEEN_PREFIX = "optikpi_inapp.t.";
/** Earlier builds stored both of those as JSON lists. Read once, then drop. */
const LEGACY_QUEUE_KEY = "optikpi_inapp.events";
const LEGACY_TRACKED_KEY = "optikpi_inapp.tracked";
const MAX_SHOWN = 500;
/** Serializes check-and-set of a shown id so two tabs cannot both claim it. */
const SHOWN_LOCK = "optikpi_inapp.shown";
/** Long enough for React to paint; short enough to recover after a terminated page. */
const SHOWN_CLAIM_LEASE_MS = 60_000;
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

function shownKey(id: string): string {
  return SHOWN_PREFIX + encodeURIComponent(id);
}

function shownClaimKey(id: string): string {
  return SHOWN_CLAIM_PREFIX + encodeURIComponent(id);
}

/**
 * A JSON list is one read-modify-write. Two tabs can read the same snapshot and the
 * later setItem drops the other tab's shown id, so that popup can appear again.
 * Each id is its own key, so one tab's write cannot replace the other's.
 */
function migrateShown(storage: Storage): void {
  if (storage.getItem(LEGACY_SHOWN_KEY) != null) {
    readList(LEGACY_SHOWN_KEY).forEach((id, index) => {
      remember(storage, shownKey(id), String(index).padStart(8, "0"));
    });
    storage.removeItem(LEGACY_SHOWN_KEY);
  }
  trimOldest(storage, SHOWN_PREFIX, MAX_SHOWN, (key) => storage.getItem(key) ?? "", "");
}

export function loadShownIds(): Set<string> {
  const storage = localStorageOrNull();
  if (!storage) return new Set();
  try {
    migrateShown(storage);
  } catch (error) {
    inAppLog(`could not read shown ids: ${error}`);
  }
  const ids = new Set<string>();
  for (const key of keysWithPrefix(storage, SHOWN_PREFIX)) {
    ids.add(decodeURIComponent(key.slice(SHOWN_PREFIX.length)));
  }
  return ids;
}

function writeShown(storage: Storage, id: string): boolean {
  migrateShown(storage);
  const key = shownKey(id);
  if (storage.getItem(key) != null) return false;
  storage.setItem(key, new Date().toISOString());
  trimOldest(storage, SHOWN_PREFIX, MAX_SHOWN, (item) => storage.getItem(item) ?? "", key);
  return true;
}

export function markShown(id: string): void {
  const storage = localStorageOrNull();
  if (!storage) return;
  try {
    writeShown(storage, id);
  } catch (error) {
    inAppLog(`could not remember shown id: ${error}`);
  }
}

export type ShownClaim = {
  id: string;
  token: string;
};

type StoredShownClaim = {
  token: string;
  expiresAt: number;
};

/**
 * - `claimed`: this call wrote a temporary lease. Finalize it after draw, or release it.
 * - `unstored`: nothing could be written (no storage, or a quota error). Show the popup anyway.
 * - `shown`: the id is already finalized as shown.
 * - `leased`: another page has a live lease; retry when `retryAfterMs` elapses.
 * - `stale`: `stillValid` was false once the lock was held, so nothing was written.
 */
export type ClaimResult =
  | { status: "claimed"; claim: ShownClaim }
  | { status: "leased"; retryAfterMs: number }
  | { status: "unstored" | "shown" | "stale" };

function withShownLock<T>(run: () => T): Promise<T> {
  const locks = typeof navigator !== "undefined" ? navigator.locks : undefined;
  if (!locks) return Promise.resolve(run());
  return locks.request(SHOWN_LOCK, run);
}

function readShownClaim(storage: Storage, id: string): StoredShownClaim | null {
  const raw = storage.getItem(shownClaimKey(id));
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<StoredShownClaim>;
    return typeof parsed.token === "string" &&
      typeof parsed.expiresAt === "number" &&
      Number.isFinite(parsed.expiresAt)
      ? { token: parsed.token, expiresAt: parsed.expiresAt }
      : null;
  } catch {
    return null;
  }
}

function claimToken(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * Leases [id] before a popup is drawn. A lease is separate from the durable shown
 * record, so a reload or crash before `didDraw` can recover it after expiry.
 */
export function claimShown(id: string, stillValid: () => boolean = () => true): Promise<ClaimResult> {
  const storage = localStorageOrNull();
  if (!storage || !id) {
    return Promise.resolve({ status: stillValid() ? "unstored" : "stale" });
  }
  return withShownLock<ClaimResult>(() => {
    if (!stillValid()) return { status: "stale" };
    try {
      migrateShown(storage);
      if (storage.getItem(shownKey(id)) != null) return { status: "shown" };
      const key = shownClaimKey(id);
      const existing = readShownClaim(storage, id);
      const now = Date.now();
      if (existing && existing.expiresAt > now) {
        return { status: "leased", retryAfterMs: existing.expiresAt - now };
      }
      // Invalid and expired leases are recoverable. This write replaces either one.
      const claim = { id, token: claimToken() };
      storage.setItem(
        key,
        JSON.stringify({ token: claim.token, expiresAt: now + SHOWN_CLAIM_LEASE_MS })
      );
      return { status: "claimed", claim };
    } catch (error) {
      inAppLog(`could not claim shown id: ${error}`);
      return { status: "unstored" };
    }
  });
}

/** Converts this lease into the durable shown record after the popup is drawn. */
export async function finalizeShown(claim: ShownClaim): Promise<void> {
  const storage = localStorageOrNull();
  if (!storage || !claim.id) return;
  try {
    await withShownLock(() => {
      const stored = readShownClaim(storage, claim.id);
      if (stored?.token !== claim.token) return;
      writeShown(storage, claim.id);
      storage.removeItem(shownClaimKey(claim.id));
    });
  } catch (error) {
    inAppLog(`could not finalize shown id: ${error}`);
  }
}

/** Takes back this lease if the popup was never drawn. A newer owner's lease is untouched. */
export async function releaseShown(claim: ShownClaim): Promise<void> {
  const storage = localStorageOrNull();
  if (!storage || !claim.id) return;
  try {
    await withShownLock(() => {
      const stored = readShownClaim(storage, claim.id);
      if (stored?.token === claim.token) storage.removeItem(shownClaimKey(claim.id));
    });
  } catch (error) {
    inAppLog(`could not release shown id: ${error}`);
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

function localStorageOrNull(): Storage | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function pendingKey(dedupeKey: string): string {
  return PENDING_PREFIX + encodeURIComponent(dedupeKey);
}

function seenKey(dedupeKey: string): string {
  return SEEN_PREFIX + encodeURIComponent(dedupeKey);
}

function keysWithPrefix(storage: Storage, prefix: string): string[] {
  const keys: string[] = [];
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (key != null && key.startsWith(prefix)) keys.push(key);
  }
  return keys;
}

function remember(
  storage: Storage,
  key: string,
  value: string
): void {
  if (storage.getItem(key) == null) storage.setItem(key, value);
}

/**
 * A JSON list is one read-modify-write. Two tabs can read the same snapshot and the
 * later setItem drops the other tab's impression or click. Each event is its own key,
 * so one tab's write cannot replace the other's.
 */
function migrateLegacyLists(storage: Storage): void {
  if (storage.getItem(LEGACY_TRACKED_KEY) != null) {
    for (const dedupeKey of readList(LEGACY_TRACKED_KEY)) {
      remember(storage, seenKey(dedupeKey), "1");
    }
    storage.removeItem(LEGACY_TRACKED_KEY);
  }
  if (storage.getItem(LEGACY_QUEUE_KEY) != null) {
    for (const raw of readList(LEGACY_QUEUE_KEY)) {
      const parsed = parseQueued(raw);
      if (!parsed) continue;
      const dedupeKey = eventDedupeKey(parsed.workspaceId, parsed.playerId, parsed.event);
      remember(storage, pendingKey(dedupeKey), raw);
      remember(storage, seenKey(dedupeKey), parsed.event.at || "1");
    }
    storage.removeItem(LEGACY_QUEUE_KEY);
  }
  trimOldest(storage, PENDING_PREFIX, MAX_QUEUED, (key) => pendingAt(storage.getItem(key)), "");
  trimOldest(storage, SEEN_PREFIX, MAX_TRACKED, (key) => storage.getItem(key) ?? "", "");
}

function pendingAt(raw: string | null): string {
  return parseQueued(raw ?? "")?.event.at ?? "";
}

/** Drops the oldest keys past `max`. `keepKey` is the event just written and is left in place. */
function trimOldest(
  storage: Storage,
  prefix: string,
  max: number,
  stamp: (key: string) => string,
  keepKey: string
): void {
  const keys = keysWithPrefix(storage, prefix);
  const extra = keys.length - max;
  if (extra <= 0) return;
  const ranked = keys
    .filter((key) => key !== keepKey)
    .map((key) => ({ key, at: stamp(key) }))
    .sort((a, b) => (a.at < b.at ? -1 : a.at > b.at ? 1 : a.key < b.key ? -1 : 1));
  for (let i = 0; i < extra && i < ranked.length; i++) {
    storage.removeItem(ranked[i].key);
  }
}

export function enqueueEvent(
  event: PopupEvent,
  workspaceId: string,
  playerId: string
): boolean {
  const storage = localStorageOrNull();
  if (!storage) return false;
  try {
    migrateLegacyLists(storage);
    const dedupeKey = eventDedupeKey(workspaceId, playerId, event);
    const seen = seenKey(dedupeKey);
    if (storage.getItem(seen) != null) return false;
    const pending = pendingKey(dedupeKey);
    storage.setItem(pending, JSON.stringify({ workspaceId, playerId, event }));
    storage.setItem(seen, event.at);
    trimOldest(storage, PENDING_PREFIX, MAX_QUEUED, (key) => pendingAt(storage.getItem(key)), pending);
    trimOldest(storage, SEEN_PREFIX, MAX_TRACKED, (key) => storage.getItem(key) ?? "", seen);
    inAppLog(`queued ${event.type} ${event.trackingId}`);
    return true;
  } catch (error) {
    inAppLog(`could not queue ${event.type}: ${error}`);
    return false;
  }
}

export function queuedFor(workspaceId: string, playerId: string): QueuedEvent[] {
  const storage = localStorageOrNull();
  if (!storage) return [];
  try {
    migrateLegacyLists(storage);
  } catch (error) {
    inAppLog(`could not read event queue: ${error}`);
  }
  const items: QueuedEvent[] = [];
  for (const key of keysWithPrefix(storage, PENDING_PREFIX)) {
    const parsed = parseQueued(storage.getItem(key) ?? "");
    if (parsed == null) continue;
    if (parsed.workspaceId !== workspaceId || parsed.playerId !== playerId) continue;
    items.push(parsed);
  }
  items.sort((a, b) => (a.event.at < b.event.at ? -1 : a.event.at > b.event.at ? 1 : 0));
  return items;
}

export function removeQueued(raws: string[]) {
  const storage = localStorageOrNull();
  if (!storage) return;
  try {
    for (const raw of raws) {
      const parsed = parseQueued(raw);
      if (!parsed) continue;
      const dedupeKey = eventDedupeKey(parsed.workspaceId, parsed.playerId, parsed.event);
      storage.removeItem(pendingKey(dedupeKey));
    }
  } catch (error) {
    inAppLog(`could not trim event queue: ${error}`);
  }
}
