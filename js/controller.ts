import { fetchPopups, PopupHttpException, sendEvents, type PopupEvent, type PopupRequestContext } from "./http";
import { inAppLog } from "./log";
import type { InAppPopup, PopupAction, PopupContent } from "./parse";
import { FetchThrottle, isRouteBlocked, pickPopupToShow } from "./rules";
import { enqueueEvent, loadShownIds, markShown, queuedFor, removeQueued } from "./storage";
import { readPlayerTokenClaims } from "./token";

export type PopupDeepLinkHandler = (link: string) => void;
export type PopupCustomActionHandler = (data: Record<string, string>) => void;

export type ActivePopup = {
  popup: InAppPopup;
  context: PopupRequestContext | null;
  onDeepLink?: PopupDeepLinkHandler;
  onCustomAction?: PopupCustomActionHandler;
  isTest: boolean;
};

type Setup = {
  apiKey: string;
  baseUrl: string;
  onDeepLink?: PopupDeepLinkHandler;
  onCustomAction?: PopupCustomActionHandler;
  blockedRoutes: string[];
};

type Session = {
  context: PopupRequestContext;
  onDeepLink?: PopupDeepLinkHandler;
  onCustomAction?: PopupCustomActionHandler;
  blockedRoutes: string[];
  throttle: FetchThrottle;
  waiting: InAppPopup[];
  fetching: boolean;
  paused: boolean;
};

export type InitOptions = {
  apiKey: string;
  playerToken: string;
  baseUrl: string;
  onDeepLink?: PopupDeepLinkHandler;
  onCustomAction?: PopupCustomActionHandler;
  blockedRoutes?: string[];
};

const IMAGE_WAIT_MS = 10_000;
const FLUSH_DELAY_MS = 2_000;
const BATCH_SIZE = 50;

export class OptikpiInAppController {
  current: ActivePopup | null = null;
  private session: Session | null = null;
  private setup: Setup | null = null;
  private lastPlayerKey: string | null = null;
  private route: string | null = null;
  private visit = 0;
  private shownThisVisit = false;
  private picking = false;
  private pickAgain = false;
  private flushTimer: ReturnType<typeof setTimeout> | null = null;
  private testCount = 0;
  private listeners = new Set<() => void>();

  get isPaused() {
    return this.session?.paused ?? false;
  }

  get currentRoute() {
    return this.route;
  }

  subscribe(listener: () => void) {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private emit() {
    this.listeners.forEach((listener) => listener());
  }

  private setCurrent(value: ActivePopup | null) {
    this.current = value;
    this.emit();
  }

  async init(options: InitOptions) {
    const setup: Setup = {
      apiKey: options.apiKey,
      baseUrl: options.baseUrl,
      onDeepLink: options.onDeepLink,
      onCustomAction: options.onCustomAction,
      blockedRoutes: options.blockedRoutes ?? [],
    };
    const context = this.contextFor(setup, options.playerToken);
    if (!context) {
      inAppLog("player token unreadable: not fetching");
      this.endSession(false);
      this.setup = setup;
      return;
    }
    await this.startSession(context, setup);
  }

  setPlayerToken(playerToken: string) {
    const setup = this.setup;
    if (!setup) {
      inAppLog("setPlayerToken before init: ignored");
      return;
    }
    const context = this.contextFor(setup, playerToken);
    if (!context) {
      inAppLog("player token unreadable: not fetching");
      if (this.session) this.session.paused = true;
      return;
    }
    const session = this.session;
    if (
      !session ||
      session.context.playerId !== context.playerId ||
      session.context.workspaceId !== context.workspaceId
    ) {
      void this.startSession(context, setup);
      return;
    }
    session.context = context;
    session.paused = false;
    inAppLog("player token replaced");
    void this.flush(session);
    this.fetchMoment();
  }

  async logout() {
    inAppLog("logout");
    this.endSession(!(this.session?.paused ?? false));
    this.setup = null;
  }

  didChangeRoute(route: string | null) {
    if (!route || route === this.route) return;
    this.route = route;
    this.visit += 1;
    this.shownThisVisit = false;
    inAppLog(`route ${route}`);
    const active = this.current;
    const session = this.session;
    if (
      active &&
      !active.isTest &&
      session &&
      isRouteBlocked(route, session.blockedRoutes)
    ) {
      inAppLog(`hide ${active.popup.id}: ${route} is blocked`);
      this.setCurrent(null);
    }
    this.showMoment();
    this.fetchMoment();
  }

  onForeground() {
    const session = this.session;
    if (!session) return;
    void this.flush(session);
    this.fetchMoment();
  }

  showTest(content: PopupContent) {
    this.testCount += 1;
    const popup: InAppPopup = {
      id: `test-${this.testCount}`,
      trackingId: `test-${this.testCount}`,
      createdAt: new Date(),
      content,
    };
    this.setCurrent({
      popup,
      context: null,
      onDeepLink: this.session?.onDeepLink,
      onCustomAction: this.session?.onCustomAction,
      isTest: true,
    });
  }

  didDraw(active: ActivePopup) {
    if (this.current !== active) return;
    if (active.isTest) {
      inAppLog(`test pop-up drawn (${active.popup.content.layout})`);
      return;
    }
    markShown(active.popup.id);
    this.track(active, "impression");
  }

  close(active: ActivePopup, dismissed: boolean) {
    if (this.current !== active) return false;
    this.setCurrent(null);
    if (dismissed) this.track(active, "dismiss");
    return true;
  }

  tapButton(active: ActivePopup, index: number) {
    if (index < 0 || index >= active.popup.content.buttons.length) return;
    if (!this.close(active, false)) return;
    this.track(active, "click", index);
    this.perform(active, active.popup.content.buttons[index].action);
  }

  tapImage(active: ActivePopup) {
    const action = active.popup.content.image?.tapAction;
    if (!action && active.popup.content.layout !== "imageOnly") return;
    if (!this.close(active, false)) return;
    this.track(active, "click");
    if (action) this.perform(active, action);
  }

  reset() {
    this.endSession(false);
    this.setup = null;
    this.lastPlayerKey = null;
    this.setCurrent(null);
    this.route = null;
    this.visit = 0;
    this.shownThisVisit = false;
    this.picking = false;
    this.pickAgain = false;
  }

  private contextFor(setup: Setup, playerToken: string): PopupRequestContext | null {
    const claims = readPlayerTokenClaims(playerToken);
    if (!claims) return null;
    return {
      baseUrl: setup.baseUrl,
      workspaceId: claims.workspaceId,
      playerId: claims.playerId,
      apiKey: setup.apiKey,
      playerToken,
    };
  }

  private async startSession(context: PopupRequestContext, setup: Setup) {
    const previous = this.session;
    const switching =
      previous != null &&
      (previous.context.playerId !== context.playerId ||
        previous.context.workspaceId !== context.workspaceId);
    this.endSession(switching && !previous.paused);
    const session: Session = {
      context,
      onDeepLink: setup.onDeepLink,
      onCustomAction: setup.onCustomAction,
      blockedRoutes: [...setup.blockedRoutes],
      throttle: new FetchThrottle(),
      waiting: [],
      fetching: false,
      paused: false,
    };
    this.session = session;
    this.setup = setup;
    const playerKey = `${context.workspaceId}/${context.playerId}`;
    if (playerKey !== this.lastPlayerKey) this.shownThisVisit = false;
    this.lastPlayerKey = playerKey;
    inAppLog(`init workspace ${context.workspaceId} player ${context.playerId}`);
    void this.flush(session);
    this.fetchMoment();
  }

  private endSession(flush: boolean) {
    const session = this.session;
    this.session = null;
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
      this.flushTimer = null;
    }
    if (this.current && !this.current.isTest) this.setCurrent(null);
    if (flush && session) void this.flush(session);
  }

  private fetchMoment() {
    const session = this.session;
    if (!session) return;
    void this.maybeFetch(session);
  }

  private inForeground() {
    if (typeof document === "undefined") return true;
    return document.visibilityState !== "hidden";
  }

  private async maybeFetch(session: Session) {
    if (
      session.paused ||
      session.fetching ||
      !this.inForeground() ||
      !session.throttle.canFetch(new Date())
    ) {
      return;
    }
    session.fetching = true;
    const context = session.context;
    let tokenChanged = false;
    try {
      const result = await fetchPopups(context);
      if (this.session !== session) return;
      session.throttle.recordResponse(new Date(), result.nextCheckAfterSec);
      session.waiting = result.popups;
      inAppLog(
        `fetched ${result.popups.length} pop-up(s), next check in ${Math.round(session.throttle.intervalMs / 1000)} s`
      );
      void this.flush(session);
      this.showMoment();
    } catch (error) {
      inAppLog(`fetch failed: ${error}`);
      if (error instanceof PopupHttpException && error.isAuthFailure) {
        tokenChanged = !this.pauseIfCurrent(session, context);
      }
    } finally {
      session.fetching = false;
    }
    if (tokenChanged && this.session === session) this.fetchMoment();
  }

  private pauseIfCurrent(session: Session, context: PopupRequestContext) {
    if (context !== session.context) return false;
    inAppLog("player token refused: paused until setPlayerToken");
    session.paused = true;
    return true;
  }

  private showMoment() {
    const session = this.session;
    if (!session) return;
    void this.maybeShow(session);
  }

  private async maybeShow(session: Session): Promise<void> {
    if (this.picking) {
      this.pickAgain = true;
      return;
    }
    if (this.shownThisVisit || this.current) return;
    this.picking = true;
    this.pickAgain = false;
    const visit = this.visit;
    let next: InAppPopup | null = null;
    try {
      next = pickPopupToShow({
        waiting: session.waiting,
        shownIds: loadShownIds(),
        route: this.route,
        blockedRoutes: session.blockedRoutes,
        now: new Date(),
      });
      const image = next?.content.image;
      if (image && !image.url.startsWith("gradient:")) {
        await Promise.race([
          preloadImage(image.url),
          new Promise((resolve) => setTimeout(resolve, IMAGE_WAIT_MS)),
        ]);
      }
    } catch (error) {
      inAppLog(`pick failed: ${error}`);
      next = null;
    } finally {
      this.picking = false;
    }
    if (this.session !== session) {
      if (this.pickAgain) this.showMoment();
      return;
    }
    if (visit !== this.visit || this.pickAgain) {
      return this.maybeShow(session);
    }
    if (!next || this.shownThisVisit || this.current) return;
    this.shownThisVisit = true;
    session.waiting = session.waiting.filter((popup) => popup.id !== next!.id);
    inAppLog(`show ${next.id} (${next.content.layout}) on ${this.route}`);
    this.setCurrent({
      popup: next,
      context: session.context,
      onDeepLink: session.onDeepLink,
      onCustomAction: session.onCustomAction,
      isTest: false,
    });
  }

  private perform(active: ActivePopup, action: PopupAction) {
    switch (action.type) {
      case "dismiss":
        return;
      case "deeplink":
        if (action.value) active.onDeepLink?.(action.value);
        return;
      case "custom":
        if (action.customData) active.onCustomAction?.(action.customData);
        return;
      case "url": {
        if (!action.value) return;
        try {
          window.open(action.value, "_blank", "noopener,noreferrer");
        } catch (error) {
          inAppLog(`could not open ${action.value}: ${error}`);
        }
      }
    }
  }

  private track(active: ActivePopup, type: PopupEvent["type"], buttonIndex?: number) {
    if (active.isTest) {
      inAppLog(`test ${type}${buttonIndex != null ? ` button ${buttonIndex}` : ""}`);
      return;
    }
    const context = active.context!;
    const event: PopupEvent = {
      trackingId: active.popup.trackingId,
      type,
      at: new Date().toISOString(),
      buttonIndex,
    };
    if (enqueueEvent(event, context.workspaceId, context.playerId)) {
      this.scheduleFlush();
    }
  }

  private scheduleFlush() {
    const session = this.session;
    if (!session) return;
    if (this.flushTimer) clearTimeout(this.flushTimer);
    this.flushTimer = setTimeout(() => {
      if (this.session === session) void this.flush(session);
    }, FLUSH_DELAY_MS);
  }

  private async flush(session: Session) {
    if (session.paused || !this.inForeground()) return;
    const context = session.context;
    try {
      const items = queuedFor(context.workspaceId, context.playerId);
      for (let start = 0; start < items.length; start += BATCH_SIZE) {
        const batch = items.slice(start, start + BATCH_SIZE);
        try {
          await sendEvents(
            context,
            batch.map((item) => item.event)
          );
        } catch (error) {
          inAppLog(`event send failed, will retry: ${error}`);
          if (error instanceof PopupHttpException && error.isAuthFailure) {
            this.pauseIfCurrent(session, context);
          }
          return;
        }
        removeQueued(batch.map((item) => item.raw));
      }
    } catch (error) {
      inAppLog(`flush failed: ${error}`);
    }
  }
}

function preloadImage(url: string) {
  return new Promise<void>((resolve) => {
    const image = new Image();
    image.onload = () => resolve();
    image.onerror = () => resolve();
    image.src = url;
  });
}

export const controller = new OptikpiInAppController();
