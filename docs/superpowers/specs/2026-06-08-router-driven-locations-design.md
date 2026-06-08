# Router-driven locations: making app state addressable

**Date:** 2026-06-08
**Status:** Approved design — ready for implementation planning
**Scope:** thedozone2 (app) + small surgical changes to the `zoned` framework router

---

## Problem

thedozone2 uses the `zoned` router for one thing: **which canvas is shown**. Everything
else that represents "where you are" in the app — help docs, sync setup, column properties —
is invisible imperative state, opened with a `.show()` call or `window.open(..., "_blank")`.

Consequences:

- Help docs eject into a new browser tab as unthemed static HTML (`/docs/*.html`).
- Overlays aren't deep-linkable, aren't restored on reload, and don't participate in
  browser back/forward.
- The framework ships capabilities — route titles, the `Zone` primitive, reverse routing,
  typed params — that the app has **never switched on**. Some are dormant; at least one
  (`RouteMetadata.title`) is dead code the Router never reads.

We are underusing the router. This spec promotes meaningful app locations to real routes.

## The unifying principle

**The router is the canonical name for "where you are" in the app.** Every meaningful
location gets a URL — so it is deep-linkable, back-button-native, titled, and restored on
reload — built on framework capabilities that already exist.

## Goals

- Help docs become themed, in-app, deep-linkable routes (no more new-tab eject).
- Sync setup (cloud + local folder) becomes addressable routes.
- Route titles drive `document.title`.
- A reusable **overlay** pattern: layered routes over a persistent canvas.
- Activate reverse routing / typed params where they read cleaner (opportunistic, not forced).

## Non-goals

- Redesigning the `zoned` router primitive (explicitly out of scope per brainstorming).
- Routing anything that lives *inside* a canvas context (see Scope boundary) — the canvas
  is a terminal wildcard, which makes nested-under-canvas routes structurally impossible.
- A search feature (does not exist today; was mistakenly assumed during exploration).

---

## Architecture

Four layers. Layer 0 is enabling framework work; Layers 1–3 are the app consumers.

### Layer 0 — Enabling fixes in `zoned`

Two small, surgical changes. Both activate capability that is dormant or dead today.

**0a. Trie precedence — match specific before wildcard.**
`RouteTrie.findRoute` (`zoned/.../routing/RouteTrie.kt`) currently checks for a wildcard
child **first** and lets it consume the entire remaining path:

```kotlin
// Check for wildcard first (it captures all remaining segments)
val wildcardChild = current.children.find { it is WildcardNode }
if (wildcardChild != null) { ... break }
```

Because the canvas route is `/{canvas...}` (a wildcard at the root), this greedily swallows
*any* top-level path — `/help/shortcuts` is read as "a canvas named help/shortcuts". No
sibling route can coexist with canvases.

**Fix:** at each node, match in standard precedence order — **static → parameter → wildcard
(fallback)**. After the change the root trie looks like:

```
Root
├── help          ← matched first (static)
│   └── {topic}
├── settings      ← matched first (static)
│   └── {section}
└── {canvas...}   ← fallback (wildcard)

/my/board        → canvas
/help/shortcuts  → help route
/settings/sync   → settings route
```

Canvas URLs are unchanged; existing bookmarks keep working.

*Reserved-namespace tradeoff:* with simple single-pass precedence (no backtracking),
top-level segments `help` and `settings` become **reserved** — a canvas literally named
`help` would be shadowed. This is acceptable and must be documented. Full backtracking
(fall back to the wildcard branch if the specific branch yields no route) is a more robust
alternative but a larger change to the trie's matching; it is an **optional follow-up**, not
part of this work. Recommendation: ship reserved-namespace precedence, document the reserved
names.

**0b. Apply `RouteMetadata.title`.**
`Route` exposes `.title { params -> "..." }` and stores it in `RouteMetadata.title`, but
`Router.handleRoute` (`zoned/.../routing/Router.kt`) **never reads it**. Dead code today.

**Fix:** after a route's handler renders (both `FULL_PAGE` and `PARTIAL` branches), if
`route.metadata.title != null`, set `document.title = route.metadata.title!!(Params(params))`.
~3 lines.

### Layer 1 — The overlay Zone pattern (thedozone2)

A single persistent overlay layer, declared via the framework's `Zone` primitive
(`Zone("overlay")`), mounted above `#app-container`.

**Rendering rules:**

- **Overlay routes** render `PARTIAL` into `#overlay` and **must not touch `#app-container`**.
  The canvas stays mounted underneath.
- **The canvas route becomes idempotent.** Today `index()` (`Index.kt:181`) unconditionally
  removes and rebuilds `#app-container` on every navigation. Change it so that when
  `#app-container` already exists for the current canvas, the canvas route **does not rebuild
  the board** — it just ensures `#overlay` is cleared. The board is only torn down/rebuilt
  when the canvas actually changes.
- **Closing an overlay** = `Router.navigate(<current canvas url>)` (or `history.back()` when
  the prior entry is the canvas). With the idempotent canvas route, this clears `#overlay`
  and leaves the board's DOM (scroll, focus, transient state) intact.

**Why not `fragment()` / `FragmentRoute`?** The framework's other dormant capability is
fragment routing (`Routes.fragment(zone, parent, ...)` + `FragmentRoute`). Its model is
"render the parent route, then render the fragment into a zone" — which would rebuild the
canvas every time an overlay opens, the opposite of what a stateful client board wants. It is
also **unfinished**: `Router.handleRoute` switches only on `RenderMode` and never inspects
`FragmentRoute.parent`/`.target`. We deliberately do **not** wire it up. The idempotent-canvas
+ overlay-zone approach is the better fit. `FragmentRoute` is noted as deferred (see Future).

### Layer 2 — Help as routes (first consumer)

Routes (each with `.title { }`):

| Route | Title |
|-------|-------|
| `/help/shortcuts` | "Keyboard Shortcuts" |
| `/help/formatting` | "Card Formatting" |
| `/help/release-notes` | "Release Notes" |

- F1 / F2 (`Index.kt:149-155`) and the menu's `openDoc` (`CanvasContextMenu.kt:120-122, 177`)
  stop calling `window.open(..., "_blank")` and call `Router.navigate("/help/...")`.
- Help content moves out of hand-maintained `src/jsMain/resources/docs/*.html` into
  **markdown**, rendered through the app's existing pipeline.

**Content rendering detail (flag for planning):** `MarkdownService.fromMd(markdown)` parses
markdown into the app's `Card` model (not generic prose HTML). The app has no generic
markdown→prose renderer; its single rendering path is cards via `CardRenderer`. So the
natural, single-path implementation is: help markdown → `List<Card>` → render **read-only**
cards into the overlay. This keeps one rendering path and auto-themes help. The open
implementation question is how much of `CardRenderer` assumes canvas/column context (drop
zones, editing, `onMount` wiring) and whether a lightweight read-only render path is needed.
Resolve during planning; the directional decision (use the app's markdown pipeline, do not
maintain separate HTML) is settled.

The three `/docs/*.html` files (and `docs.css`) are removed once migrated.

### Layer 3 — Sync settings as routes (second consumer)

| Route | Renders | Title |
|-------|---------|-------|
| `/settings/sync` | `SyncModal` | "Cloud Sync" |
| `/settings/folder` | `LocalFolderModal` | "Local Folder Sync" |

The modals render into the overlay zone (same pattern as help). The `.show()` call sites
become `Router.navigate(...)`:

- `SyncBootstrap` (`sync/SyncBootstrap.kt:29,35`)
- `CanvasContextMenu` (`view/CanvasContextMenu.kt:166-167`)
- `SyncIndicator` (`view/sync/SyncIndicator.kt:79`)

This proves the overlay pattern generalizes beyond docs. Modal internals (forms, sync logic)
are unchanged — only how they are opened and where they mount.

---

## Scope boundary — what stays non-routed, and why

- **Column properties (`ColumnPropertiesModal`), per-column CARD/EDITOR mode** — these live
  *inside* a canvas. The canvas route is a terminal wildcard `/{canvas...}`, so a URL like
  `/{canvas...}/column/3` is **structurally impossible**: the wildcard consumes `column/3` as
  canvas segments. Routing them would require canvas to stop being a terminal wildcard — a
  different, larger project that was not chosen. They stay imperative.
- **Context menus, canvas switcher (`PageDown`), wormhole hover preview** — cursor-transient.
  Correctly *not* addressable; leave as-is.

## Smaller dead-capability activations (opportunistic)

- **Reverse routing.** Canvas links are hand-built (`CardRenderer.kt:541`) with a comment that
  `Route.path` "requires a fixed param count". The `Route.path(map)` overload *does* support
  wildcards (`segments.joinToString` handles `RouteSegment.Wildcard` via `params[name]`), so
  links could become `App.index.canvas.path(mapOf("canvas" to ...))`. Marginal readability
  win; include only where it clearly reads better. Not load-bearing for this work.
- **Typed params** (`Params.int/long/boolean`). Nothing here needs them; activate only if a
  concrete route benefits. Do not force.

---

## Error handling

- **Unknown help topic / settings section** (`/help/bogus`): the route either does not match
  (→ existing `Router.notFound`) or, if we use a `{topic}` param, the handler renders a small
  themed "unknown help topic" panel in the overlay with a link back. Prefer explicit static
  routes per topic over a param, so unknown topics simply don't match.
- **Reserved namespace collision**: a canvas named `help`/`settings` is shadowed. Documented
  limitation; optionally validate/repath on canvas creation (out of scope here).
- **Overlay open with no canvas mounted** (e.g. deep link straight to `/help/shortcuts` on a
  fresh load): the overlay route must ensure a canvas is rendered underneath first (render the
  current/last canvas into `#app-container`, then the overlay). Closing returns to that canvas.

## Testing

- **`zoned` (jvmTest):** extend `RouteTest` to cover precedence — static and param siblings
  win over a wildcard; wildcard still matches when no specific sibling matches; reserved-name
  shadowing behaves as documented.
- **Title metadata:** unit-level assertion that a route with `.title { }` sets the expected
  string (the title function is pure and testable independent of the DOM).
- **App (jsTest where feasible):** navigating to a help/settings route mounts `#overlay`
  without tearing down `#app-container`; closing clears `#overlay` and preserves the board;
  navigating between two canvases still rebuilds the board.
- **Manual:** F1/F2 open in-app (not a new tab); deep link to each help/settings route on cold
  load; browser back closes the overlay; `document.title` updates per route.

## Risks

- **Idempotent canvas route** is the subtlest change — getting "rebuild only when the canvas
  actually changed" wrong could either leak stale board DOM or fail to clear overlays. Needs
  careful identity check (current canvas name vs. target) and explicit overlay-clear.
- **Read-only card rendering** for help may surface coupling in `CardRenderer` (drop zones,
  edit affordances). Mitigation: a read-only render path / flag; sized during planning.
- **Trie precedence change** affects every `zoned` consumer, not just thedozone2. Mitigated by
  the precedence being the standard, more-correct semantics and by added `RouteTest` coverage.

## Future / explicitly deferred

- **Complete `FragmentRoute`** in the Router (parent-then-fragment-into-zone). Not needed here;
  revisit if a use case wants true nested fragments that re-render a parent.
- **Trie backtracking** to remove the reserved-namespace limitation.
- **Canvas-context routes** (column properties, per-column mode) — requires rethinking the
  canvas wildcard scheme.

## Build sequence

1. **Layer 0a** — trie precedence in `zoned` + `RouteTest` coverage; publish to Maven Local.
2. **Layer 0b** — apply `RouteMetadata.title` in `Router`; republish.
3. **Layer 1** — overlay `Zone` + idempotent canvas route in thedozone2 (no consumer yet).
4. **Layer 2** — help routes; migrate `/docs/*.html` → markdown via the pipeline; rewire
   F1/F2 + menu; delete static docs.
5. **Layer 3** — settings routes; rewire `SyncModal` / `LocalFolderModal` open sites.
6. **Opportunistic** — reverse routing / typed params where they read cleaner.
