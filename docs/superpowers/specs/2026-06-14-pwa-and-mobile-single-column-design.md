# PWA install + mobile single-column — Design

Date: 2026-06-14
Branch: router-driven-locations

## Goal

Two related improvements to The Do Zone (Kotlin/JS canvas app):

- **A.** Offer PWA installation via an in-app menu button (no automatic browser prompt).
- **B.** On mobile (viewport width ≤ 768px), show and allow interaction with **only the
  first column**; all other columns are absent from the DOM.

## Decisions (locked)

- Install surface: a **menu item**, shown only when the browser reports the app is
  installable. No auto-banner.
- Mobile rule: **strictly the first column** — other columns are hidden and unreachable.
- Mobile definition: **`window.innerWidth <= 768`** (pure viewport width; a narrowed
  desktop window also becomes single-column).
- Service worker: **full offline shell** (cache app shell; navigations network-first so
  deploys update; static assets stale-while-revalidate).
- "First column" = the first **leaf** (content) column in layout order — the top-left-most,
  found by walking the layout tree taking the first `contains` child to a leaf.

---

## Part A — PWA install

### A1. Make the app installable

Three gaps block installability today:

1. `src/jsMain/resources/site.webmanifest` has empty `name`/`short_name`.
   → Set `name: "The Do Zone"`, `short_name: "Do Zone"`. Keep existing icons (192/512),
   `display: standalone`, theme/background colors. Add `start_url: "/"` and
   `scope: "/"`.
2. `src/jsMain/resources/index.html` has no viewport meta.
   → Add `<meta name="viewport" content="width=device-width, initial-scale=1,
   viewport-fit=cover">` (also required for Part B to render correctly).
3. No service worker.
   → Add `src/jsMain/resources/sw.js` (served at `/sw.js`, root scope). Register it.

### A2. Service worker (`sw.js`)

A hand-written static file (not processed by the Kotlin/webpack build).

- **Cache name** carries a version constant (e.g. `tdz-shell-v1`); bumped on meaningful
  SW changes. `activate` deletes caches whose name ≠ current.
- **`install`**: pre-cache the app shell — `/`, `/index.html`, `/main.bundle.js`
  (stable webpack output name, referenced in index.html), `/IBM_VGA_8x16.woff`,
  `/style.css`, the manifest, and the icon set. `skipWaiting()`.
- **`fetch`** strategy:
  - **Navigation requests** (`request.mode === "navigate"`): network-first, fall back to
    cached `/index.html` when offline. Keeps deploys live.
  - **Same-origin static assets**: stale-while-revalidate — serve cache immediately,
    refresh in background, so a redeployed `main.bundle.js` is picked up on the next load
    even though the filename is stable.
  - **Cross-origin** (Google Fonts CSS/files, GIS `accounts.google.com` script):
    best-effort cache-first; failures pass through to network. Never block on these.
  - Only `GET` requests are handled; everything else passes through.
- `clients.claim()` on activate.

### A3. Registration

Register the SW on load. To capture `beforeinstallprompt` reliably (it can fire before the
Kotlin bundle finishes booting), add a **tiny inline script in `index.html` `<head>`** that:

```html
<script>
  window.__tdzDeferredInstall = null;
  window.addEventListener('beforeinstallprompt', function (e) {
    e.preventDefault();
    window.__tdzDeferredInstall = e;
  });
  if ('serviceWorker' in navigator) {
    window.addEventListener('load', function () {
      navigator.serviceWorker.register('/sw.js').catch(function () {});
    });
  }
</script>
```

### A4. `PwaInstall` (Kotlin)

New small object, e.g. `src/jsMain/kotlin/tdz/view/PwaInstall.kt`:

- `isAvailable(): Boolean` — true when `window.__tdzDeferredInstall` is non-null and the
  app is not already running standalone
  (`!matchMedia("(display-mode: standalone)").matches`).
- `prompt()` — calls `.prompt()` on the stashed event, then clears
  `window.__tdzDeferredInstall` (a deferred prompt is single-use). Best-effort; guarded.

External JS interop via dynamic access to `window.__tdzDeferredInstall`.

### A5. Menu item

Add to `canvasSections` (in `CanvasContextMenu.kt`) — the app's only menu. Natural home:
a new item near Help, or its own top section.

- `MenuItem(label = "Install app", enabled = true, onSelect = actions::installApp)`,
  **conditionally included only when `PwaInstall.isAvailable()`** so it never shows when
  not installable or already installed.
- Add `installApp()` to `CanvasMenuActions` + its implementation in `canvasMenu` calling
  `PwaInstall.prompt()`.
- Unit test (`CanvasContextMenuTest` style): item present/absent driven by an injected
  availability flag, mirroring the existing `localFolderSupported` pattern (keep
  `canvasSections` pure — pass install-availability in as a parameter rather than calling
  `PwaInstall` inside the builder).

iOS Safari never fires `beforeinstallprompt`, so the item simply won't appear there. Out of
scope: a static "Add to Home Screen" instruction item for iOS (YAGNI).

---

## Part B — Mobile single-column

### B1. First-leaf resolution

Add a helper on `CanvasColumns` (or `Canvas`): walk from `canvas.root()` taking the first
entry of each layout's `contains` until reaching a column not in `layoutMap`. That id is the
"first column". Falls back to the existing default-focus computation if the tree is
degenerate.

### B2. Mobile detection

A single source of truth, e.g. `fun isMobile(): Boolean = window.innerWidth <= 768`
(constant `MOBILE_BREAKPOINT = 768`). Used by render and the resize listener.

### B3. Render-time single column

In `CanvasColumns.render(regenerateColumns)`:

- If **not mobile** → current behavior (render the full layout tree).
- If **mobile** → render **only the first leaf column**, full viewport, bypassing
  `renderLayoutTree`. Reuse `setupColumnContainer` + the existing leaf-column `div`
  (id, `columnCss`, `paint()`, `onMount { setupColumnHandlers }`) so the one visible column
  behaves exactly as on desktop (cards, editor toggle on double-tap, task toggle, drag).
- `focusedColumn` is set to the first leaf so focus styling/keyboard targets are coherent.
- The column-scrollbar stylesheet rule still applies.

Other columns are never built → no Ace editors, not in the DOM, truly non-interactable.
Keyboard navigation (`navigateFocus`) becomes a no-op in practice on touch; no change
needed (arrows simply won't move since only one column exists).

### B4. Breakpoint crossing

Add a single **app-level debounced `window` resize listener** (installed once from
`Index`/`main`, not per-render — avoids listener churn across canvas swaps) that tracks the
last known mobile state. When `isMobile()` flips, fire
`RefreshCanvas(currentCanvas, structural = false)` — the existing refresh path
(`Index.setupRefreshHandler`) swaps the canvas zone and re-renders in the new mode. Debounce
(~150ms) avoids thrashing during continuous resize/rotation. Only re-render on an actual
flip, not every resize.

## Files touched

- `src/jsMain/resources/site.webmanifest` — name/short_name/start_url/scope.
- `src/jsMain/resources/index.html` — viewport meta + inline install/SW script.
- `src/jsMain/resources/sw.js` — **new**, offline shell.
- `src/jsMain/kotlin/tdz/view/PwaInstall.kt` — **new**.
- `src/jsMain/kotlin/tdz/view/CanvasContextMenu.kt` — install menu item + action +
  `canvasSections` param.
- `src/jsMain/kotlin/tdz/view/CanvasColumns.kt` — first-leaf helper, mobile render branch.
- `src/jsMain/kotlin/tdz/Index.kt` — app-level breakpoint-flip resize listener.
- Tests: menu-item visibility; first-leaf resolution; mobile render (one column only).

## Testing

- **Unit (Kotlin/JS, karma):**
  - `canvasSections` includes/excludes "Install app" per the injected availability flag.
  - First-leaf resolver returns the top-left leaf for nested layouts and flat layouts.
  - Mobile render: with width forced ≤ 768 (stub/override), only one column element is
    produced; desktop width produces the full tree. (Follow existing `CanvasColumns*Test`
    patterns; inject width via the `isMobile`/breakpoint seam rather than real `window`.)
- **Manual:** install flow on desktop Chrome (right-click → Install app); offline reload
  after first visit; mobile/responsive ≤768 shows only first column and it's fully usable;
  rotate / resize across the breakpoint re-renders correctly.

## Out of scope

- iOS "Add to Home Screen" instructional UI.
- Mobile column switching / swipe navigation (explicitly: strictly first column).
- Push notifications, background sync, or any SW feature beyond offline shell.
