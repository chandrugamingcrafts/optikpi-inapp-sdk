import { fetchPopups, PopupHttpException, sendEvents, type PopupEvent, type PopupRequestContext } from "./http.js";
import { inAppLog } from "./log.js";
import type { InAppPopup, PopupAction, PopupContent } from "./parse.js";
import { FetchThrottle, isPopupExpired, isRouteBlocked, pickPopupToShow } from "./rules.js";
import {
  claimShown,
  enqueueEvent,
  finalizeShown,
  loadShownIds,
  markShown,
  queuedFor,
  releaseShown,
  removeQueued,
  type ClaimResult,
  type ShownClaim,
} from "./storage.js";
import { readPlayerTokenClaims } from "./token.js";

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
  flushing: boolean;
  flushAgain: boolean;
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

function rendersPopupImage(content: PopupContent): boolean {
  if (!content.image) return false;
  switch (content.layout) {
    case "toast":
      return content.size === "md";
    case "bottomSheet":
      return content.size !== "sm";
    case "modal":
      return content.size !== "iab_300x250";
    case "banner":
    case "imageOnly":
      return true;
  }
}

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
  /** Lease for `current` until `didDraw` finalizes it. Released if `current` is cleared first. */
  private undrawnClaim: ShownClaim | null = null;
  private claimRetryTimer: ReturnType<typeof setTimeout> | null = null;
  private flushTimer: ReturnType<typeof setTimeout> | null = null;
  private testCount = 0;
  private listeners = new Set<() => void>();
  private lifecycleBound = false;
  private resumeQueued = false;

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
    const previous = this.current;
    this.current = value;
    this.emit();
    if (
      previous &&
      !previous.isTest &&
      previous.popup.id === this.undrawnClaim?.id &&
      value?.popup.id !== previous.popup.id
    ) {
      this.releaseUndrawn(previous.popup.id);
    }
  }

  private releaseUndrawn(id: string) {
    if (this.undrawnClaim?.id !== id) return;
    const claim = this.undrawnClaim;
    this.undrawnClaim = null;
    inAppLog(`release ${id}: not drawn`);
    void releaseShown(claim);
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
    this.unbindLifecycle();
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
    this.showMoment();
  }

  /**
   * A hidden tab skips fetch, popup selection and flush. The page resumes all three
   * when it is shown again, or restored from the back-forward cache. Both events
   * in one turn resume once.
   */
  private bindLifecycle() {
    if (this.lifecycleBound || typeof document === "undefined" || typeof window === "undefined") {
      return;
    }
    document.addEventListener("visibilitychange", this.onVisibility);
    window.addEventListener("pageshow", this.onPageShow);
    this.lifecycleBound = true;
  }

  private unbindLifecycle() {
    if (!this.lifecycleBound || typeof document === "undefined" || typeof window === "undefined") {
      return;
    }
    document.removeEventListener("visibilitychange", this.onVisibility);
    window.removeEventListener("pageshow", this.onPageShow);
    this.lifecycleBound = false;
  }

  private onVisibility = () => {
    if (typeof document !== "undefined" && document.visibilityState === "visible") {
      this.queueResume();
    }
  };

  private onPageShow = (event: PageTransitionEvent) => {
    if (event.persisted) this.queueResume();
  };

  private queueResume() {
    if (this.resumeQueued) return;
    this.resumeQueued = true;
    queueMicrotask(() => {
      this.resumeQueued = false;
      this.onForeground();
    });
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
    if (this.undrawnClaim?.id === active.popup.id) {
      const claim = this.undrawnClaim;
      this.undrawnClaim = null;
      void finalizeShown(claim);
    } else {
      // Storage may have been unavailable when selection ran.
      markShown(active.popup.id);
    }
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
    this.unbindLifecycle();
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
      flushing: false,
      flushAgain: false,
    };
    this.session = session;
    this.setup = setup;
    const playerKey = `${context.workspaceId}/${context.playerId}`;
    if (playerKey !== this.lastPlayerKey) this.shownThisVisit = false;
    this.lastPlayerKey = playerKey;
    inAppLog(`init workspace ${context.workspaceId} player ${context.playerId}`);
    this.bindLifecycle();
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
    if (this.claimRetryTimer) {
      clearTimeout(this.claimRetryTimer);
      this.claimRetryTimer = null;
    }
    if (this.current && !this.current.isTest) this.setCurrent(null);
    else if (this.undrawnClaim) this.releaseUndrawn(this.undrawnClaim.id);
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

  private retryClaimAtExpiry(session: Session, retryAfterMs: number) {
    if (this.claimRetryTimer) clearTimeout(this.claimRetryTimer);
    // A small cushion avoids firing in the same millisecond that the lease expires.
    this.claimRetryTimer = setTimeout(() => {
      this.claimRetryTimer = null;
      if (this.session === session) this.showMoment();
    }, Math.max(0, Math.ceil(retryAfterMs)) + 25);
  }

  private async maybeShow(session: Session): Promise<void> {
    if (this.picking) {
      this.pickAgain = true;
      return;
    }
    // A fetch or route change can land while the tab is hidden. Claiming then
    // would spend the once-per-device popup on a message nobody saw.
    if (!this.inForeground()) return;
    if (this.shownThisVisit || this.current) return;
    this.picking = true;
    this.pickAgain = false;
    const visit = this.visit;
    let next: InAppPopup | null = null;
    let claim: ClaimResult | null = null;
    // True while nothing has changed since this pick began. Checked before the claim
    // and again inside its lock, where a wait for another tab can span a route change.
    const stillValid = () =>
      this.session === session &&
      visit === this.visit &&
      !this.pickAgain &&
      !this.shownThisVisit &&
      !this.current &&
      this.inForeground();
    let claimed: ShownClaim | null = null;
    const giveBack = () => {
      if (!claimed) return;
      const claimToRelease = claimed;
      claimed = null;
      inAppLog(`release ${claimToRelease.id}: not shown`);
      void releaseShown(claimToRelease);
    };
    try {
      next = pickPopupToShow({
        waiting: session.waiting,
        shownIds: loadShownIds(),
        route: this.route,
        blockedRoutes: session.blockedRoutes,
        now: new Date(),
      });
      const image = next?.content.image;
      if (next && image && rendersPopupImage(next.content) && !image.url.startsWith("gradient:")) {
        await Promise.race([
          preloadImage(image.url),
          new Promise((resolve) => setTimeout(resolve, IMAGE_WAIT_MS)),
        ]);
      }
      if (next && isPopupExpired(next, new Date())) {
        inAppLog(`skip ${next.id}: expired while its image loaded`);
        session.waiting = session.waiting.filter((popup) => popup.id !== next!.id);
        next = null;
        this.pickAgain = true;
      }
      if (next && stillValid()) {
        // Lease before setCurrent. didDraw finalizes the id only after paint, while
        // the lease stops another tab from drawing the same popup in the meantime.
        claim = await claimShown(next.id, stillValid);
        if (claim.status === "claimed") claimed = claim.claim;
        if (claim.status === "shown") {
          inAppLog(`skip ${next.id}: already shown`);
          session.waiting = session.waiting.filter((popup) => popup.id !== next!.id);
          next = null;
          this.pickAgain = true;
        } else if (claim.status === "leased") {
          inAppLog(`retry ${next.id}: another page holds a temporary claim`);
          this.retryClaimAtExpiry(session, claim.retryAfterMs);
          // Keep it in waiting. The expiry timer will select it again without a fetch.
          next = null;
        }
      }
    } catch (error) {
      inAppLog(`pick failed: ${error}`);
      next = null;
      giveBack();
    } finally {
      this.picking = false;
    }
    if (this.session !== session) {
      giveBack();
      if (this.pickAgain) this.showMoment();
      return;
    }
    if (visit !== this.visit || this.pickAgain) {
      giveBack();
      return this.maybeShow(session);
    }
    if (
      !next ||
      (claim?.status !== "claimed" && claim?.status !== "unstored") ||
      this.shownThisVisit ||
      this.current ||
      !this.inForeground()
    ) {
      giveBack();
      return;
    }
    // Keep the claim until didDraw. A blocked route, logout, or a replacement
    // popup can clear current before the overlay effect runs, and that clear
    // releases an id that never produced an impression.
    const held = claim.status === "claimed" ? claimed : null;
    claimed = null;
    if (this.undrawnClaim && this.undrawnClaim.token !== held?.token) {
      this.releaseUndrawn(this.undrawnClaim.id);
    }
    this.undrawnClaim = held;
    if (this.claimRetryTimer) {
      clearTimeout(this.claimRetryTimer);
      this.claimRetryTimer = null;
    }
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
    if (session.flushing) {
      session.flushAgain = true;
      return;
    }
    session.flushing = true;
    try {
      do {
        session.flushAgain = false;
        if (session.paused || !this.inForeground()) return;
        if (!(await this.flushPass(session))) return;
      } while (session.flushAgain);
    } finally {
      const retry = session.flushAgain;
      session.flushAgain = false;
      session.flushing = false;
      // The coalescing timer has already fired. Preserve that request after a
      // failed in-flight pass by scheduling another attempt.
      if (retry && this.session === session) this.scheduleFlush();
    }
  }

  /** One queue snapshot. A trigger during this pass sets `flushAgain` for a fresh snapshot. */
  private async flushPass(session: Session): Promise<boolean> {
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
          return false;
        }
        removeQueued(batch.map((item) => item.raw));
      }
    } catch (error) {
      inAppLog(`flush failed: ${error}`);
      return false;
    }
    return true;
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
