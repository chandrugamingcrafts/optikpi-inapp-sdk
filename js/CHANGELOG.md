# Changelog

## Unreleased

- `package.json` makes `optikpi_inapp` installable from `js/` (`dist/index.js`). Apps depend on it with `file:` so the documented import resolves.
- Each queued event is stored under its own `localStorage` key, so two tabs can enqueue at the same time without one write dropping the other.
- `OptikpiInAppOverlay` is exported from `optikpi_inapp/overlay` only. Importing `optikpi_inapp` no longer loads React or the overlay CSS.
- The overlay reads `controller.current` with `useSyncExternalStore`, so a popup that arrives while the component is mounting is still drawn.
- JWT payloads are decoded as UTF-8, so a non-ASCII `sub` or `wid` matches a token that JSON-escaped the same characters.
- Built modules import each other with `.js` extensions, so `import("optikpi_inapp")` loads under Node's native ESM.
- A hidden tab's skipped fetch and event flush run when the tab becomes visible, or the page is restored from the back-forward cache. Hosts do not call `onForeground` for that.
- Each shown popup id is stored under its own `localStorage` key, so two tabs cannot drop each other's once-per-device record.
- An image-only card shrinks to the viewport height as well as the width, so the close button stays on screen in a short or landscape window.
- A modal, bottom sheet, or image-only popup moves focus inside itself, marks the rest of the page inert, and restores focus when it closes.

## 0.1.0

First release of the web SDK. It follows `API.md` the same way the Flutter SDK does.
- `OptikpiInApp.init` / `setPlayerToken` / `logout` / `didChangeRoute` / `onForeground`, with the same options as Flutter: `onDeepLink`, `onCustomAction`, `blockedRoutes`.
- Fetches `GET {baseUrl}/v1/inapp/popups` and sends events to `POST {baseUrl}/ingest/popup` with `Authorization: Bearer` and `x-player-token`.
- Draws all six layouts (modal, fullscreen, bottom sheet, banner, toast, image-only) with `OptikpiInAppOverlay`, a React component. Modals are centered dialogs at 768px and wider, and full-width cards on phones.
- Events are queued in `localStorage`, deduplicated per workspace, player, `trackingId` and type, and retried until accepted. Storage failures never block a button's action.
- Pauses on `401` or `403` until `setPlayerToken`. A token for another player starts that player from nothing.
- Shown popup ids are remembered per device in `localStorage`.
