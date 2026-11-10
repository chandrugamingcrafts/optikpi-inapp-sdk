# optikpi_inapp (Web JS)

The web SDK of [optikpi-inapp-sdk](../README.md): OptiKPI in-app pop-ups for websites. It fetches
the player's waiting pop-ups, draws them over the page, and reports impression, click and dismiss.
It follows [`API.md`](../API.md). The Flutter SDK is in [`flutter/`](../flutter/).

It is plain TypeScript apart from one React component, `OptikpiInAppOverlay`. Any site can call the
core API. React is needed only to mount the overlay.

## Integration

**1. Copy `js/` into your project** (or add it as a package). Import the overlay CSS through your
bundler, as `overlay.css` is imported by `overlay.tsx`.

**2. Mount the overlay once**, near the root of the site, on every page:

```tsx
import { OptikpiInAppOverlay } from "optikpi_inapp";

export default function Root({ children }) {
  return (
    <>
      {children}
      <OptikpiInAppOverlay />
    </>
  );
}
```

**3. Call `init` after the player signs in**, with a `playerToken` from your server. Your server signs
it; see [`docs/player-token-server.md`](../docs/player-token-server.md).

```ts
import { OptikpiInApp } from "optikpi_inapp";

await OptikpiInApp.init({
  apiKey: "...",                       // the workspace's in-app key, sent as a Bearer token
  playerToken: loginResponse.optikpiPlayerToken,
  baseUrl: "https://app.optikpi.com/apigw",
  blockedRoutes: ["/checkout", "/login"],
  onDeepLink: (link) => router.push(link),              // optional
  onCustomAction: (data) => handleMessageClick(data),   // optional
});
OptikpiInApp.didChangeRoute(location.pathname);         // on each page change
```

**4. Call `setPlayerToken` when the server gives you a new token**, and `logout` on sign-out:

```ts
OptikpiInApp.setPlayerToken(newToken);
await OptikpiInApp.logout();
```

Notes:
- Browsers can't call the OptiKPI gateway directly from your domain without CORS. Either allow your
  origin on the gateway, or proxy `GET /v1/inapp/popups` and `POST /ingest/popup` through your server
  and pass that proxy as `baseUrl`.
- `OptikpiInApp.debug = true` logs what the SDK does with the prefix `[OptikpiInApp]`.

## Files

| File | |
|---|---|
| `index.ts` | Public API: `OptikpiInApp` and `OptikpiInAppOverlay`. |
| `controller.ts` | Fetch, show, taps, queue and pause logic. |
| `http.ts` | The two OptiKPI calls. |
| `parse.ts` | Parses popups and applies the contract's fallbacks. |
| `rules.ts` | Blocked routes, which popup to show, fetch throttle. |
| `token.ts` | Reads `sub` and `wid` from the player token, unverified. |
| `storage.ts` | `localStorage`: shown ids, event queue, dedupe keys. |
| `overlay.tsx`, `overlay.css` | The React overlay and its layout by screen width. |
| `log.ts`, `version.ts` | Debug logging and SDK version. |

See [`CHANGELOG.md`](CHANGELOG.md) for releases.
