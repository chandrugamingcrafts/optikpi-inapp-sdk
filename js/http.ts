import { inAppLog } from "./log";
import { parseFetchResult, type PopupFetchResult } from "./parse";
import { SDK_VERSION_HEADER } from "./version";

export type PopupRequestContext = {
  baseUrl: string;
  workspaceId: string;
  playerId: string;
  apiKey: string;
  playerToken: string;
};

export type PopupEventType = "impression" | "click" | "dismiss";

export type PopupEvent = {
  trackingId: string;
  type: PopupEventType;
  at: string;
  buttonIndex?: number;
};

export class PopupHttpException extends Error {
  constructor(
    readonly statusCode: number,
    readonly path: string
  ) {
    super(`HTTP ${statusCode} from ${path}`);
  }

  get isAuthFailure() {
    return this.statusCode === 401 || this.statusCode === 403;
  }
}

function headers(context: PopupRequestContext, extra?: Record<string, string>) {
  return {
    authorization: `Bearer ${context.apiKey}`,
    "x-player-token": context.playerToken,
    "x-sdk-version": SDK_VERSION_HEADER,
    accept: "application/json",
    ...extra,
  };
}

function uri(context: PopupRequestContext, path: string) {
  const base = context.baseUrl.endsWith("/")
    ? context.baseUrl.slice(0, -1)
    : context.baseUrl;
  return `${base}${path}`;
}

export async function fetchPopups(context: PopupRequestContext): Promise<PopupFetchResult> {
  const path = "/v1/inapp/popups";
  const response = await fetch(uri(context, path), {
    method: "GET",
    headers: headers(context),
    cache: "no-store",
  });
  if (response.status !== 200) throw new PopupHttpException(response.status, path);
  return parseFetchResult(await response.json());
}

export async function sendEvents(
  context: PopupRequestContext,
  events: PopupEvent[]
): Promise<void> {
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
