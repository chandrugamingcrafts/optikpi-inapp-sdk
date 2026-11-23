package com.optikpi.inapp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.optikpi.inapp.events.EventQueue;
import com.optikpi.inapp.events.PopupEvent;
import com.optikpi.inapp.events.PopupEventType;
import com.optikpi.inapp.logic.FetchThrottle;
import com.optikpi.inapp.logic.PlayerTokenClaims;
import com.optikpi.inapp.logic.ShowRules;
import com.optikpi.inapp.logic.ShownStore;
import com.optikpi.inapp.model.InAppPopup;
import com.optikpi.inapp.model.PopupAction;
import com.optikpi.inapp.model.PopupButton;
import com.optikpi.inapp.model.PopupContent;
import com.optikpi.inapp.model.PopupEnums.PopupLayout;
import com.optikpi.inapp.model.PopupFetchResult;
import com.optikpi.inapp.render.PopupImageLoader;
import com.optikpi.inapp.source.HttpPopupSource;
import com.optikpi.inapp.source.PopupHttpException;
import com.optikpi.inapp.source.PopupRequestContext;
import com.optikpi.inapp.source.PopupSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OptikpiInAppController {
  public static final long IMAGE_WAIT_MS = 10_000;
  public static final long FLUSH_DELAY_MS = 2_000;

  private static OptikpiInAppController instance;

  private final Context appContext;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService workers = Executors.newSingleThreadExecutor();
  private final EventQueue events;
  private final ShownStore shown;
  private final PopupImageLoader images;
  private final List<CurrentListener> listeners = new CopyOnWriteArrayList<>();

  // The main thread changes these and the worker thread reads them, hence volatile.
  private volatile Setup setup;
  private volatile Session session;
  private String lastPlayerKey;
  private volatile ActivePopup current;
  // Nothing is on screen until a lifecycle callback says so: a process woken for a background
  // component must not fetch or flush.
  private volatile boolean inForeground = false;
  private Runnable flushTimer;
  private int testCount;

  private volatile String route;
  private volatile int visit;
  private volatile boolean shownThisVisit;
  private boolean picking;
  private boolean pickAgain;

  public interface CurrentListener {
    void onChanged(ActivePopup active);
  }

  private static final class Setup {
    final String apiKey;
    final String baseUrl;
    final PopupSource source;
    final ActivePopup.DeepLinkHandler onDeepLink;
    final ActivePopup.CustomActionHandler onCustomAction;
    final List<String> blockedRoutes;

    Setup(
        String apiKey,
        String baseUrl,
        PopupSource source,
        ActivePopup.DeepLinkHandler onDeepLink,
        ActivePopup.CustomActionHandler onCustomAction,
        List<String> blockedRoutes) {
      this.apiKey = apiKey;
      this.baseUrl = baseUrl;
      this.source = source;
      this.onDeepLink = onDeepLink;
      this.onCustomAction = onCustomAction;
      this.blockedRoutes = Collections.unmodifiableList(new ArrayList<>(blockedRoutes));
    }
  }

  private static final class Session {
    volatile PopupRequestContext context;
    final PopupSource source;
    final ActivePopup.DeepLinkHandler onDeepLink;
    final ActivePopup.CustomActionHandler onCustomAction;
    final List<String> blockedRoutes;
    final FetchThrottle throttle = new FetchThrottle();
    volatile List<InAppPopup> waiting = Collections.emptyList();
    volatile boolean fetching;
    volatile boolean paused;

    Session(
        PopupRequestContext context,
        PopupSource source,
        ActivePopup.DeepLinkHandler onDeepLink,
        ActivePopup.CustomActionHandler onCustomAction,
        List<String> blockedRoutes) {
      this.context = context;
      this.source = source;
      this.onDeepLink = onDeepLink;
      this.onCustomAction = onCustomAction;
      this.blockedRoutes = blockedRoutes;
    }
  }

  private OptikpiInAppController(Context context) {
    appContext = context.getApplicationContext();
    events = new EventQueue(appContext, 50);
    shown = new ShownStore(appContext);
    images = new PopupImageLoader();
  }

  public static synchronized OptikpiInAppController getInstance(Context context) {
    if (instance == null) {
      instance = new OptikpiInAppController(context);
    }
    return instance;
  }

  public static OptikpiInAppController getInstance() {
    if (instance == null) {
      throw new IllegalStateException("OptikpiInApp not initialised");
    }
    return instance;
  }

  public PopupImageLoader getImages() {
    return images;
  }

  /** The screen path in force, or null before the first one is reported. */
  public String getRoute() {
    return route;
  }

  public ActivePopup getCurrent() {
    return current;
  }

  public void addCurrentListener(CurrentListener listener) {
    listeners.add(listener);
  }

  public void removeCurrentListener(CurrentListener listener) {
    listeners.remove(listener);
  }

  private void setCurrent(ActivePopup value) {
    ActivePopup previous = current;
    if (previous != null && previous.autoDismissTask != null) {
      // A closed pop-up must not stay reachable from the main handler for its whole delay.
      main.removeCallbacks(previous.autoDismissTask);
      previous.autoDismissTask = null;
    }
    current = value;
    for (CurrentListener listener : listeners) {
      listener.onChanged(value);
    }
  }

  public void setForeground(boolean value) {
    if (inForeground == value) {
      return;
    }
    inForeground = value;
    if (value) {
      // A pop-up published while the app was away can have expired since: don't show it now.
      ActivePopup active = current;
      if (active != null && !active.isTest && active.popup.isExpiredAt(Instant.now())) {
        InAppLog.log("drop " + active.popup.id + ": it expired while the app was in the background");
        setCurrent(null);
        showMoment(); // an older waiting pop-up may still have this visit
      }
      Session s = session;
      if (s != null) {
        flush(s);
        fetchMoment();
      }
    }
  }

  public void initWithToken(
      String apiKey,
      String playerToken,
      String baseUrl,
      PopupSource source,
      ActivePopup.DeepLinkHandler onDeepLink,
      ActivePopup.CustomActionHandler onCustomAction,
      List<String> blockedRoutes) {
    Setup newSetup =
        new Setup(apiKey, baseUrl, source, onDeepLink, onCustomAction, blockedRoutes);
    PopupRequestContext context = contextFor(newSetup, playerToken);
    if (context == null) {
      InAppLog.log("player token unreadable: not fetching");
      endSession(false);
      setup = newSetup;
      return;
    }
    init(context, source, onDeepLink, onCustomAction, blockedRoutes);
  }

  private PopupRequestContext contextFor(Setup setup, String playerToken) {
    PlayerTokenClaims claims = PlayerTokenClaims.tryRead(playerToken);
    if (claims == null && setup.source.needsPlayerToken()) {
      return null;
    }
    return new PopupRequestContext(
        setup.baseUrl,
        claims != null ? claims.workspaceId : "mock",
        claims != null ? claims.playerId : "mock",
        setup.apiKey,
        playerToken);
  }

  public void setPlayerToken(String playerToken) {
    Setup s = setup;
    if (s == null) {
      InAppLog.log("setPlayerToken before init: ignored");
      return;
    }
    Session existing = session;
    PopupRequestContext context = contextFor(s, playerToken);
    if (context == null) {
      InAppLog.log("player token unreadable: not fetching");
      if (existing != null) {
        existing.paused = true;
      }
      return;
    }
    if (existing == null
        || !existing.context.playerId.equals(context.playerId)
        || !existing.context.workspaceId.equals(context.workspaceId)) {
      init(context, s.source, s.onDeepLink, s.onCustomAction, s.blockedRoutes);
      return;
    }
    existing.context = context;
    existing.paused = false;
    InAppLog.log("player token replaced");
    flush(existing);
    fetchMoment();
  }

  public void init(
      PopupRequestContext context,
      PopupSource source,
      ActivePopup.DeepLinkHandler onDeepLink,
      ActivePopup.CustomActionHandler onCustomAction,
      List<String> blockedRoutes) {
    Session previous = session;
    boolean switching =
        previous != null
            && (!previous.context.playerId.equals(context.playerId)
                || !previous.context.workspaceId.equals(context.workspaceId));
    endSession(switching && previous != null && !previous.paused);
    Session newSession =
        new Session(
            context,
            source,
            onDeepLink,
            onCustomAction,
            Collections.unmodifiableList(new ArrayList<>(blockedRoutes)));
    session = newSession;
    setup =
        new Setup(
            context.apiKey,
            context.baseUrl,
            source,
            onDeepLink,
            onCustomAction,
            blockedRoutes);
    String playerKey = context.workspaceId + "/" + context.playerId;
    if (!playerKey.equals(lastPlayerKey)) {
      shownThisVisit = false;
    }
    lastPlayerKey = playerKey;
    workers.execute(
        () -> {
          shown.load();
          main.post(
              () -> {
                if (session != newSession) {
                  return;
                }
                InAppLog.log("init workspace " + context.workspaceId + " player " + context.playerId);
                flush(newSession);
                fetchMoment();
              });
        });
  }

  public void logout() {
    InAppLog.log("logout");
    endSession(session != null && !session.paused);
    setup = null;
  }

  private void endSession(boolean flush) {
    Session s = session;
    session = null;
    if (flushTimer != null) {
      main.removeCallbacks(flushTimer);
      flushTimer = null;
    }
    ActivePopup active = current;
    if (active != null && !active.isTest) {
      setCurrent(null);
    }
    if (flush && s != null) {
      workers.execute(() -> flushQuiet(s));
    }
  }

  private void fetchMoment() {
    Session s = session;
    if (s == null) {
      return;
    }
    workers.execute(() -> maybeFetch(s));
  }

  private void maybeFetch(Session s) {
    if (session != s || s.paused || s.fetching || !inForeground || !s.throttle.canFetch(SystemClock.elapsedRealtime())) {
      return;
    }
    s.fetching = true;
    PopupRequestContext context = s.context;
    boolean tokenChanged = false;
    try {
      PopupFetchResult result = s.source.fetch(context);
      if (session != s) {
        return;
      }
      s.throttle.recordResponse(SystemClock.elapsedRealtime(), result.nextCheckAfterSec);
      s.waiting = result.popups;
      InAppLog.log(
          "fetched "
              + result.popups.size()
              + " pop-up(s), next check in "
              + s.throttle.getInterval().getSeconds()
              + " s");
      flush(s);
      main.post(this::showMoment);
    } catch (PopupHttpException e) {
      InAppLog.log("fetch failed: " + e);
      if (e.isAuthFailure()) {
        tokenChanged = !pauseIfCurrent(s, context);
      }
    } catch (Exception e) {
      InAppLog.log("fetch failed: " + e);
    } finally {
      s.fetching = false;
    }
    if (tokenChanged && session == s) {
      fetchMoment();
    }
  }

  private boolean pauseIfCurrent(Session s, PopupRequestContext context) {
    if (s.context != context) {
      return false;
    }
    InAppLog.log("player token refused: paused until setPlayerToken");
    s.paused = true;
    return true;
  }

  public void didChangeRoute(String newRoute) {
    if (newRoute == null || newRoute.equals(route)) {
      return;
    }
    route = newRoute;
    visit++;
    shownThisVisit = false;
    InAppLog.log("route " + newRoute);
    ActivePopup active = current;
    Session s = session;
    if (active != null
        && !active.isTest
        && s != null
        && ShowRules.isRouteBlocked(newRoute, s.blockedRoutes)) {
      InAppLog.log("hide " + active.popup.id + ": " + newRoute + " is blocked");
      setCurrent(null);
    }
    showMoment();
    fetchMoment();
  }

  private void showMoment() {
    Session s = session;
    if (s == null) {
      return;
    }
    workers.execute(() -> maybeShow(s));
  }

  private void maybeShow(Session s) {
    if (picking) {
      pickAgain = true;
      return;
    }
    if (shownThisVisit || current != null) {
      return;
    }
    picking = true;
    pickAgain = false;
    int visitSnapshot = visit;
    InAppPopup next = null;
    try {
      var shownIds = shown.load();
      next =
          ShowRules.pickPopupToShow(
              s.waiting, shownIds, route, s.blockedRoutes, Instant.now());
      if (next != null && next.content.image != null && !next.content.image.isGradient()) {
        images.awaitLoad(next.content.image.url, IMAGE_WAIT_MS);
      }
    } catch (Exception e) {
      InAppLog.log("pick failed: " + e);
      next = null;
    } finally {
      picking = false;
    }
    if (session != s) {
      if (pickAgain) {
        showMoment();
      }
      return;
    }
    if (visitSnapshot != visit || pickAgain) {
      maybeShow(s);
      return;
    }
    if (next == null || shownThisVisit || current != null) {
      return;
    }
    InAppPopup picked = next;
    int pickedVisit = visitSnapshot;
    // The pick ran on the worker thread. A logout or a screen change can land on the main thread
    // between that check and this post, so decide again where the state changes, in one go.
    main.post(() -> publish(s, picked, pickedVisit));
  }

  /** Main thread. Shows [picked] only if the world is still the one it was picked for. */
  private void publish(Session s, InAppPopup picked, int pickedVisit) {
    if (session != s
        || visit != pickedVisit
        || shownThisVisit
        || current != null
        || ShowRules.isRouteBlocked(route, s.blockedRoutes)) {
      InAppLog.log("drop " + picked.id + ": the session or screen changed before it showed");
      return;
    }
    // Not consumed yet: the pop-up stays waiting, and the visit unspent, until it is really drawn
    // (see didDraw). One that is dropped before that, expired during the image wait or hidden by
    // a screen change, can then be tried again instead of being lost.
    InAppLog.log("show " + picked.id + " (" + picked.content.layout.name() + ") on " + route);
    setCurrent(new ActivePopup(picked, s.context, s.onDeepLink, s.onCustomAction, false));
  }

  public void showTest(PopupContent content) {
    testCount++;
    InAppPopup popup =
        new InAppPopup(
            "test-" + testCount,
            "test-" + testCount,
            null,
            Instant.now(),
            null,
            content);
    ActivePopup.DeepLinkHandler deepLink = session != null ? session.onDeepLink : null;
    ActivePopup.CustomActionHandler custom = session != null ? session.onCustomAction : null;
    if (setup != null) {
      deepLink = deepLink != null ? deepLink : setup.onDeepLink;
      custom = custom != null ? custom : setup.onCustomAction;
    }
    setCurrent(new ActivePopup(popup, null, deepLink, custom, true));
  }

  public void didDraw(ActivePopup active) {
    if (current != active || active.drawn) {
      return;
    }
    if (!active.isTest && active.popup.isExpiredAt(Instant.now())) {
      InAppLog.log("drop " + active.popup.id + ": it expired before it was drawn");
      setCurrent(null);
      showMoment(); // the next waiting pop-up, if any, still has this visit
      return;
    }
    active.drawn = true;
    if (active.isTest) {
      InAppLog.log("test pop-up drawn (" + active.getContent().layout.name() + ")");
      return;
    }
    consume(active);
    workers.execute(
        () -> {
          try {
            shown.markShown(active.popup.id);
          } catch (Exception e) {
            InAppLog.log("could not remember " + active.popup.id + ": " + e);
          }
        });
    track(active, PopupEventType.impression, null);
    Integer autoDismiss = active.getContent().autoDismissSec;
    if (autoDismiss != null) {
      Runnable task = () -> close(active, true);
      active.autoDismissTask = task;
      main.postDelayed(task, autoDismiss * 1000L);
    }
  }

  /** The pop-up was drawn: it is spent for this visit and leaves the waiting list. */
  private void consume(ActivePopup active) {
    shownThisVisit = true;
    Session s = session;
    if (s == null) {
      return;
    }
    List<InAppPopup> remaining = new ArrayList<>();
    for (InAppPopup p : s.waiting) {
      if (!p.id.equals(active.popup.id)) {
        remaining.add(p);
      }
    }
    s.waiting = Collections.unmodifiableList(remaining);
  }

  public boolean close(ActivePopup active, boolean dismissed) {
    if (current != active) {
      return false;
    }
    setCurrent(null);
    if (dismissed) {
      track(active, PopupEventType.dismiss, null);
    }
    return true;
  }

  public void tapButton(ActivePopup active, int index) {
    if (index < 0 || index >= active.getContent().buttons.size()) {
      return;
    }
    if (!close(active, false)) {
      return;
    }
    PopupButton button = active.getContent().buttons.get(index);
    // The event carries the button's place in the delivered array, which differs from [index]
    // when an earlier button was dropped for an unsupported action.
    track(active, PopupEventType.click, button.sourceIndex);
    perform(active, button.action);
  }

  public void tapImage(ActivePopup active) {
    PopupAction action = active.getContent().image != null ? active.getContent().image.tapAction : null;
    if (action == null && active.getContent().layout != PopupLayout.imageOnly) {
      return;
    }
    if (!close(active, false)) {
      return;
    }
    track(active, PopupEventType.click, null);
    if (action != null) {
      perform(active, action);
    }
  }

  private void perform(ActivePopup active, PopupAction action) {
    switch (action.type) {
      case dismiss -> {}
      case deeplink -> {
        if (action.value != null && active.onDeepLink != null) {
          active.onDeepLink.onDeepLink(action.value);
        }
      }
      case custom -> {
        if (action.customData != null && active.onCustomAction != null) {
          active.onCustomAction.onCustomAction(action.customData);
        }
      }
      case url -> {
        if (action.value == null) {
          return;
        }
        try {
          Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(action.value));
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          appContext.startActivity(intent);
        } catch (Exception e) {
          InAppLog.log("could not open " + action.value + ": " + e);
        }
      }
    }
  }

  private void track(ActivePopup active, PopupEventType type, Integer buttonIndex) {
    if (active.isTest) {
      String suffix = buttonIndex != null ? " button " + buttonIndex : "";
      InAppLog.log("test " + type.name() + suffix);
      return;
    }
    PopupRequestContext context = active.context;
    PopupEvent event =
        new PopupEvent(active.popup.trackingId, type, Instant.now(), buttonIndex);
    workers.execute(
        () -> {
          if (events.enqueue(event, context.workspaceId, context.playerId)) {
            scheduleFlush();
          }
        });
  }

  private void scheduleFlush() {
    Session s = session;
    if (s == null) {
      return;
    }
    if (flushTimer != null) {
      main.removeCallbacks(flushTimer);
    }
    flushTimer =
        () -> {
          if (session == s) {
            flush(s);
          }
        };
    main.postDelayed(flushTimer, FLUSH_DELAY_MS);
  }

  private void flush(Session s) {
    if (s.paused || !inForeground) {
      return;
    }
    workers.execute(
        () -> {
          // This task may have waited behind a fetch or an image load: the app can have gone to
          // the background, the session ended, or an earlier request been refused since.
          if (session != s || s.paused || !inForeground) {
            return;
          }
          // The request and the pause decision must use the same token: setPlayerToken can swap
          // s.context while the POST is in flight, and a refusal of the old token says nothing
          // about the new one.
          PopupRequestContext sentWith = s.context;
          try {
            events.flush(s.source, sentWith);
          } catch (PopupHttpException e) {
            if (e.isAuthFailure()) {
              if (!pauseIfCurrent(s, sentWith) && session == s) {
                // The token was replaced mid-request: the queued events still need sending.
                flush(s);
              }
            }
          } catch (Exception e) {
            InAppLog.log("flush failed: " + e);
          }
        });
  }

  private void flushQuiet(Session s) {
    try {
      events.flush(s.source, s.context);
    } catch (Exception e) {
      InAppLog.log("flush failed: " + e);
    }
  }

  public PopupSource defaultHttpSource() {
    return new HttpPopupSource();
  }
}