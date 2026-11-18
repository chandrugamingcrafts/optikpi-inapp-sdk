import { inAppLog } from "./log";
import { parseFetchResult } from "./parse";
import { SDK_VERSION_HEADER } from "./version";
export class PopupHttpException extends Error {
    constructor(statusCode, path) {
        super(`HTTP ${statusCode} from ${path}`);
        Object.defineProperty(this, "statusCode", {
            enumerable: true,
            configurable: true,
            writable: true,
            value: statusCode
        });
        Object.defineProperty(this, "path", {
            enumerable: true,
            configurable: true,
            writable: true,
            value: path
        });
    }
    get isAuthFailure() {
        return this.statusCode === 401 || this.statusCode === 403;
    }
}
function headers(context, extra) {
    return {
        authorization: `Bearer ${context.apiKey}`,
        "x-player-token": context.playerToken,
        "x-sdk-version": SDK_VERSION_HEADER,
        accept: "application/json",
        ...extra,
    };
}
function uri(context, path) {
    const base = context.baseUrl.endsWith("/")
        ? context.baseUrl.slice(0, -1)
        : context.baseUrl;
    return `${base}${path}`;
}
export async function fetchPopups(context) {
    const path = "/v1/inapp/popups";
    const response = await fetch(uri(context, path), {
        method: "GET",
        headers: headers(context),
        cache: "no-store",
    });
    if (response.status !== 200)
        throw new PopupHttpException(response.status, path);
    return parseFetchResult(await response.json());
}
export async function sendEvents(context, events) {
    const path = "/ingest/popup";
    const response = await fetch(uri(context, path), {
        method: "POST",
        headers: headers(context, { "content-type": "application/json" }),
        body: JSON.stringify({ events }),
    });
    if (response.status < 200 || response.status >= 300) {
        throw new PopupHttpException(response.status, path);
    }
    inAppLog(`sent ${events.length} event(s)`);
}
//# sourceMappingURL=http.js.map