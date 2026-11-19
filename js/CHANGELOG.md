# Changelog

## Unreleased

- `package.json` makes `optikpi_inapp` installable from `js/` (`dist/index.js`). Apps depend on it with `file:` so the documented import resolves.
- Each queued event is stored under its own `localStorage` key, so two tabs can enqueue at the same time without one write dropping the other.
- `OptikpiInAppOverlay` is exported from `optikpi_inapp/overlay` only. Importing `optikpi_inapp` no longer loads React or the overlay CSS.
- The overlay reads `controller.current` with `useSyncExternalStore`, so a popup that arrives while the component is mounting is still drawn.
- JWT payloads are decoded as UTF-8, so a non-ASCII `sub` or `wid` matches a token that JSON-escaped the same characters.
- Built modules import each other with `.js` extensions, so `import("optikpi_inapp")` loads under Node's native ESM.
- A hidden tab's skipped fetch, popup selection and event flush run when the tab becomes visible, or the page is restored from the back-forward cache. A popup is not claimed while the tab is hidden. Hosts do not call `onForeground` for that.
- Each shown popup id is stored under its own `localStorage` key, so two tabs cannot drop each other's once-per-device record.
- An image-only card shrinks to the viewport height as well as the width, so the close button stays on screen in a short or landscape window.
- A modal, bottom sheet, or image-only popup moves focus inside itself, marks the rest of the page inert, and restores focus when it closes.
- A popup id is claimed across tabs before it is drawn, so two visible tabs do not show the same once-per-device message.
- After an image finishes loading, `expiresAt` is checked again before the popup id is claimed, so a campaign that expired during the wait is not shown.
- A bottom-sheet image keeps its side margins without extending past the panel, so the sheet does not scroll sideways.
- The shown-id claim rechecks route, session and tab state once it holds the cross-tab lock, and an id claimed for a popup that is then not drawn is released, so a once-per-device campaign is not lost to a change during the wait.
- An `iab_320x50` banner uses `border-box`, so its 12px side padding stays inside the 320×50 size instead of widening the card to 344px.
- Secondary button borders and image placeholders apply transparency to the parsed theme color, so `#RGB` and `#RRGGBBAA` stay valid CSS instead of becoming `#fff33` or `#1018288033`.
- A shown-id claim stays reversible until the overlay reports the popup drawn. Clearing it first, on a blocked route, logout, or another popup, releases the id so a message that never appeared is not stored as shown.
- A pre-draw shown claim is an expiring lease, separate from the durable shown record. `didDraw` finalizes it, while a reload, crash, or hard navigation before draw leaves only a stale lease that a later page can recover.
- A live lease is kept distinct from a finalized shown record. The popup remains in the waiting snapshot and selection retries at lease expiry, so an immediate reload does not discard an un-impressed campaign.
- Overlapping event flush requests are coalesced per session. Only one queue snapshot is sent at a time, followed by a serialized pass for events queued while that request was in flight.
- Banners and toasts announce their text in a polite live region and leave focus where it is, so a screen reader hears a short-lived toast instead of missing it.
- Fixed banners honor the top, bottom, left, and right CSS safe-area insets, so their text and controls stay clear of notches, status areas, and home indicators.
- Top-right and bottom-right toasts include the applicable CSS safe-area offsets and constrain their available width between the left and right insets.
- `fullscreen` is not drawn. A popup with that layout is rejected while parsing, so it shows nothing and sends no events.

## 0.1.0

First release of the web SDK. It follows `API.md` the same way the Flutter SDK does.
- `OptikpiInApp.init` / `setPlayerToken` / `logout` / `didChangeRoute` / `onForeground`, with the same options as Flutter: `onDeepLink`, `onCustomAction`, `blockedRoutes`.
- Fetches `GET {baseUrl}/v1/inapp/popups` and sends events to `POST {baseUrl}/ingest/popup` with `Authorization: Bearer` and `x-player-token`.
- Draws five layouts (modal, bottom sheet, banner, toast, image-only) with `OptikpiInAppOverlay`, a React component. `fullscreen` is not supported. Modals are centered dialogs at 768px and wider, and full-width cards on phones.
- Events are queued in `localStorage`, deduplicated per workspace, player, `trackingId` and type, and retried until accepted. Storage failures never block a button's action.
- Pauses on `401` or `403` until `setPlayerToken`. A token for another player starts that player from nothing.
- Shown popup ids are remembered per device in `localStorage`.
