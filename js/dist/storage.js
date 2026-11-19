import { inAppLog } from "./log.js";
/** One shown popup id. The suffix is the id, so two tabs never share a list. */
const SHOWN_PREFIX = "optikpi_inapp.s.";
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
const MAX_QUEUED = 500;
const MAX_TRACKED = 2000;
function readList(key) {
    if (typeof window === "undefined")
        return [];
    try {
        const raw = window.localStorage.getItem(key);
        if (!raw)
            return [];
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed.filter((item) => typeof item === "string") : [];
    }
    catch {
        return [];
    }
}
function shownKey(id) {
    return SHOWN_PREFIX + encodeURIComponent(id);
}
/**
 * A JSON list is one read-modify-write. Two tabs can read the same snapshot and the
 * later setItem drops the other tab's shown id, so that popup can appear again.
 * Each id is its own key, so one tab's write cannot replace the other's.
 */
function migrateShown(storage) {
    if (storage.getItem(LEGACY_SHOWN_KEY) != null) {
        readList(LEGACY_SHOWN_KEY).forEach((id, index) => {
            remember(storage, shownKey(id), String(index).padStart(8, "0"));
        });
        storage.removeItem(LEGACY_SHOWN_KEY);
    }
    trimOldest(storage, SHOWN_PREFIX, MAX_SHOWN, (key) => storage.getItem(key) ?? "", "");
}
export function loadShownIds() {
    const storage = localStorageOrNull();
    if (!storage)
        return new Set();
    try {
        migrateShown(storage);
    }
    catch (error) {
        inAppLog(`could not read shown ids: ${error}`);
    }
    const ids = new Set();
    for (const key of keysWithPrefix(storage, SHOWN_PREFIX)) {
        ids.add(decodeURIComponent(key.slice(SHOWN_PREFIX.length)));
    }
    return ids;
}
export function markShown(id) {
    const storage = localStorageOrNull();
    if (!storage)
        return;
    try {
        migrateShown(storage);
        const key = shownKey(id);
        if (storage.getItem(key) != null)
            return;
        storage.setItem(key, new Date().toISOString());
        trimOldest(storage, SHOWN_PREFIX, MAX_SHOWN, (item) => storage.getItem(item) ?? "", key);
    }
    catch (error) {
        inAppLog(`could not remember shown id: ${error}`);
    }
}
function parseQueued(raw) {
    try {
        const json = JSON.parse(raw);
        const event = json.event;
        if (typeof json.workspaceId !== "string" ||
            typeof json.playerId !== "string" ||
            !event ||
            typeof event.trackingId !== "string" ||
            typeof event.type !== "string" ||
            typeof event.at !== "string") {
            return null;
        }
        return {
            raw,
            workspaceId: json.workspaceId,
            playerId: json.playerId,
            event,
        };
    }
    catch {
        return null;
    }
}
function eventDedupeKey(workspaceId, playerId, event) {
    return `${workspaceId}|${playerId}|${event.trackingId}|${event.type}`;
}
function localStorageOrNull() {
    if (typeof window === "undefined")
        return null;
    try {
        return window.localStorage;
    }
    catch {
        return null;
    }
}
function pendingKey(dedupeKey) {
    return PENDING_PREFIX + encodeURIComponent(dedupeKey);
}
function seenKey(dedupeKey) {
    return SEEN_PREFIX + encodeURIComponent(dedupeKey);
}
function keysWithPrefix(storage, prefix) {
    const keys = [];
    for (let i = 0; i < storage.length; i++) {
        const key = storage.key(i);
        if (key != null && key.startsWith(prefix))
            keys.push(key);
    }
    return keys;
}
function remember(storage, key, value) {
    if (storage.getItem(key) == null)
        storage.setItem(key, value);
}
/**
 * A JSON list is one read-modify-write. Two tabs can read the same snapshot and the
 * later setItem drops the other tab's impression or click. Each event is its own key,
 * so one tab's write cannot replace the other's.
 */
function migrateLegacyLists(storage) {
    if (storage.getItem(LEGACY_TRACKED_KEY) != null) {
        for (const dedupeKey of readList(LEGACY_TRACKED_KEY)) {
            remember(storage, seenKey(dedupeKey), "1");
        }
        storage.removeItem(LEGACY_TRACKED_KEY);
    }
    if (storage.getItem(LEGACY_QUEUE_KEY) != null) {
        for (const raw of readList(LEGACY_QUEUE_KEY)) {
            const parsed = parseQueued(raw);
            if (!parsed)
                continue;
            const dedupeKey = eventDedupeKey(parsed.workspaceId, parsed.playerId, parsed.event);
            remember(storage, pendingKey(dedupeKey), raw);
            remember(storage, seenKey(dedupeKey), parsed.event.at || "1");
        }
        storage.removeItem(LEGACY_QUEUE_KEY);
    }
    trimOldest(storage, PENDING_PREFIX, MAX_QUEUED, (key) => pendingAt(storage.getItem(key)), "");
    trimOldest(storage, SEEN_PREFIX, MAX_TRACKED, (key) => storage.getItem(key) ?? "", "");
}
function pendingAt(raw) {
    return parseQueued(raw ?? "")?.event.at ?? "";
}
/** Drops the oldest keys past `max`. `keepKey` is the event just written and is left in place. */
function trimOldest(storage, prefix, max, stamp, keepKey) {
    const keys = keysWithPrefix(storage, prefix);
    const extra = keys.length - max;
    if (extra <= 0)
        return;
    const ranked = keys
        .filter((key) => key !== keepKey)
        .map((key) => ({ key, at: stamp(key) }))
        .sort((a, b) => (a.at < b.at ? -1 : a.at > b.at ? 1 : a.key < b.key ? -1 : 1));
    for (let i = 0; i < extra && i < ranked.length; i++) {
        storage.removeItem(ranked[i].key);
    }
}
export function enqueueEvent(event, workspaceId, playerId) {
    const storage = localStorageOrNull();
    if (!storage)
        return false;
    try {
        migrateLegacyLists(storage);
        const dedupeKey = eventDedupeKey(workspaceId, playerId, event);
        const seen = seenKey(dedupeKey);
        if (storage.getItem(seen) != null)
            return false;
        const pending = pendingKey(dedupeKey);
        storage.setItem(pending, JSON.stringify({ workspaceId, playerId, event }));
        storage.setItem(seen, event.at);
        trimOldest(storage, PENDING_PREFIX, MAX_QUEUED, (key) => pendingAt(storage.getItem(key)), pending);
        trimOldest(storage, SEEN_PREFIX, MAX_TRACKED, (key) => storage.getItem(key) ?? "", seen);
        inAppLog(`queued ${event.type} ${event.trackingId}`);
        return true;
    }
    catch (error) {
        inAppLog(`could not queue ${event.type}: ${error}`);
        return false;
    }
}
export function queuedFor(workspaceId, playerId) {
    const storage = localStorageOrNull();
    if (!storage)
        return [];
    try {
        migrateLegacyLists(storage);
    }
    catch (error) {
        inAppLog(`could not read event queue: ${error}`);
    }
    const items = [];
    for (const key of keysWithPrefix(storage, PENDING_PREFIX)) {
        const parsed = parseQueued(storage.getItem(key) ?? "");
        if (parsed == null)
            continue;
        if (parsed.workspaceId !== workspaceId || parsed.playerId !== playerId)
            continue;
        items.push(parsed);
    }
    items.sort((a, b) => (a.event.at < b.event.at ? -1 : a.event.at > b.event.at ? 1 : 0));
    return items;
}
export function removeQueued(raws) {
    const storage = localStorageOrNull();
    if (!storage)
        return;
    try {
        for (const raw of raws) {
            const parsed = parseQueued(raw);
            if (!parsed)
                continue;
            const dedupeKey = eventDedupeKey(parsed.workspaceId, parsed.playerId, parsed.event);
            storage.removeItem(pendingKey(dedupeKey));
        }
    }
    catch (error) {
        inAppLog(`could not trim event queue: ${error}`);
    }
}
//# sourceMappingURL=storage.js.map