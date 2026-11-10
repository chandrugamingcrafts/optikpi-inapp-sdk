# Changelog

## 0.1.0

First release of the web SDK. It follows `API.md` the same way the Flutter SDK does.
- `OptikpiInApp.init` / `setPlayerToken` / `logout` / `didChangeRoute` / `onForeground`, with the same options as Flutter: `onDeepLink`, `onCustomAction`, `blockedRoutes`.
- Fetches `GET {baseUrl}/v1/inapp/popups` and sends events to `POST {baseUrl}/ingest/popup` with `Authorization: Bearer` and `x-player-token`.
- Draws all six layouts (modal, fullscreen, bottom sheet, banner, toast, image-only) with `OptikpiInAppOverlay`, a React component. Modals are centered dialogs at 768px and wider, and full-width cards on phones.
- Events are queued in `localStorage`, deduplicated per workspace, player, `trackingId` and type, and retried until accepted. Storage failures never block a button's action.
- Pauses on `401` or `403` until `setPlayerToken`. A token for another player starts that player from nothing.
- Shown popup ids are remembered per device in `localStorage`.
