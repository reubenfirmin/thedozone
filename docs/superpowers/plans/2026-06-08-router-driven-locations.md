# Router-driven Locations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make help docs and sync-setup addressable router locations (deep-linkable, titled, back-button-native) rendered as overlays over a persistent canvas, by switching on framework capabilities that already exist in `zoned`.

**Architecture:** Two small surgical fixes in the `zoned` framework (trie precedence: static-before-wildcard; apply `RouteMetadata.title` to `document.title`). Then in thedozone2: an overlay `#overlay` layer over a now-idempotent canvas route, with `/help/*` routes rendering help markdown through the app's `CardRenderer`, and `/settings/*` routes opening the existing sync modals. Anything that lives *inside* a canvas (column properties, per-column mode) stays imperative — the canvas is a terminal wildcard and can't host nested routes.

**Tech Stack:** Kotlin/JS (Kotlin Multiplatform), `zoned` framework, kotlinx.html (zoned fork), Gradle. Tests: `kotlin.test` — zoned via `jsNodeTest` (node), thedozone2 via `jsBrowserTest` (karma).

**Two repositories are involved:**
- `zoned` framework: `/expanse/code/startup/zoned`
- app: `/expanse/code/startup/thedozone2`

Spec: `docs/superpowers/specs/2026-06-08-router-driven-locations-design.md`

---

## File structure

**zoned (framework):**
- Modify: `src/jsMain/kotlin/zoned/framework/routing/RouteTrie.kt` — static→param→wildcard precedence; add `clear()`.
- Modify: `src/jsMain/kotlin/zoned/framework/routing/Router.kt` — apply `route.metadata.title` to `document.title`.
- Modify: `build.gradle.kts` — add `jsTest` source set + `kotlin("test")` dependency.
- Create: `src/jsTest/kotlin/zoned/framework/routing/RouteTrieTest.kt` — precedence + title-metadata tests.

**thedozone2 (app):**
- Create: `src/jsMain/kotlin/tdz/view/Overlay.kt` — the `#overlay` layer (scrim + panel + close).
- Create: `src/jsMain/kotlin/tdz/view/help/HelpContent.kt` — help markdown + `HelpTopic` enum.
- Modify: `src/jsMain/kotlin/tdz/Index.kt` — `canvasUrl`/`currentCanvasUrl`, idempotent `index()`, `openHelp`/`openSyncSettings`/`openFolderSettings`/`ensureCanvasMounted`, F1/F2 + Escape keys.
- Modify: `src/jsMain/kotlin/tdz/Main.kt` — register help/settings routes; reorder `SyncBootstrap.init` before `Router.start()`.
- Modify: `src/jsMain/kotlin/tdz/sync/SyncBootstrap.kt` — `dismissModals()`; pass `onDismiss`; route Ctrl+Shift+D.
- Modify: `src/jsMain/kotlin/tdz/view/sync/SyncModal.kt` and `LocalFolderModal.kt` — `onDismiss` param; scrim click → `onDismiss`.
- Modify: `src/jsMain/kotlin/tdz/view/sync/SyncIndicator.kt` — (via `SyncBootstrap`) open via navigation.
- Modify: `src/jsMain/kotlin/tdz/view/CanvasContextMenu.kt` — `openDoc`/`openCloudSync`/`openLocalFolderSync` navigate to routes.
- Create: `src/jsTest/kotlin/tdz/view/HelpContentTest.kt`, `src/jsTest/kotlin/tdz/CanvasUrlTest.kt`.
- Delete: `src/jsMain/resources/docs/keyboard-shortcuts.html`, `card-formatting.html`, `release-notes.html`, `docs.css`.

---

## Phase A — zoned framework (Layer 0)

### Task 1: Trie precedence — match static before wildcard

**Files:**
- Modify: `/expanse/code/startup/zoned/build.gradle.kts` (sourceSets)
- Modify: `/expanse/code/startup/zoned/src/jsMain/kotlin/zoned/framework/routing/RouteTrie.kt`
- Create: `/expanse/code/startup/zoned/src/jsTest/kotlin/zoned/framework/routing/RouteTrieTest.kt`

- [ ] **Step 1: Add a `jsTest` source set to zoned**

In `/expanse/code/startup/zoned/build.gradle.kts`, inside `sourceSets { ... }`, next to the existing `val jvmTest by getting { ... }` block, add:

```kotlin
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
```

- [ ] **Step 2: Add `clear()` to `RouteTrie` for test isolation**

`RouteTrie` is an `object` (shared mutable state across tests). In `RouteTrie.kt`, add this method inside the object (e.g. just after `private val root = RootNode()`):

```kotlin
    /** Reset all registered routes. Intended for test isolation. */
    fun clear() {
        root.children.clear()
    }
```

- [ ] **Step 3: Write the failing precedence test**

Create `/expanse/code/startup/zoned/src/jsTest/kotlin/zoned/framework/routing/RouteTrieTest.kt`:

```kotlin
package zoned.framework.routing

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class RouteTrieTest {

    @BeforeTest fun setup() = RouteTrie.clear()
    @AfterTest fun teardown() = RouteTrie.clear()

    private fun noop(): TagConsumerHandler = { _ -> Unit }

    @Test
    fun `static sibling wins over wildcard`() {
        RouteCreator.addRoute("/help/shortcuts", handler = noop())
        RouteCreator.addRoute("/{canvas...}", handler = noop())

        val help = RouteTrie.findRoute("/help/shortcuts")
        assertNotNull(help)
        assertEquals("/help/shortcuts", help.first.pattern)

        val canvas = RouteTrie.findRoute("/my/board")
        assertNotNull(canvas)
        assertEquals("/{canvas...}", canvas.first.pattern)
        assertEquals("my/board", canvas.second["canvas"])
    }

    @Test
    fun `wildcard still matches single segment when no static sibling`() {
        RouteCreator.addRoute("/{canvas...}", handler = noop())
        val canvas = RouteTrie.findRoute("/standalone")
        assertNotNull(canvas)
        assertEquals("standalone", canvas.second["canvas"])
    }

    @Test
    fun `reserved prefix with no terminal route does not fall through to wildcard`() {
        RouteCreator.addRoute("/help/shortcuts", handler = noop())
        RouteCreator.addRoute("/{canvas...}", handler = noop())
        // "/help" matches the static `help` branch but has no RouteNode there -> not found
        // (it must NOT be reinterpreted as a canvas named "help").
        assertNull(RouteTrie.findRoute("/help"))
    }
}
```

Add the small handler-type alias the test references — put it at the bottom of `RouteTrieTest.kt`:

```kotlin
import kotlinx.html.TagConsumer
import web.html.HTMLElement
typealias TagConsumerHandler = TagConsumer<HTMLElement>.(Params) -> Any
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd /expanse/code/startup/zoned && ./gradlew --no-daemon jsNodeTest --tests 'zoned.framework.routing.RouteTrieTest'`
Expected: FAIL — `static sibling wins over wildcard` fails because the current trie matches the wildcard first, so `/help/shortcuts` resolves to `/{canvas...}` (and `reserved prefix...` likely returns the wildcard route instead of null).

- [ ] **Step 5: Fix `findRoute` precedence**

In `RouteTrie.kt`, replace the body of the `while (segmentIndex < segments.size) { ... }` loop inside `findRoute` with static→param→wildcard precedence:

```kotlin
        while (segmentIndex < segments.size) {
            val segment = segments[segmentIndex]

            // 1. Static match (most specific) wins.
            val staticChild = current.children.find { it is StringNode && it.value == segment }
            if (staticChild != null) {
                current = staticChild
                segmentIndex++
                continue
            }

            // 2. Then a single-segment parameter.
            val paramChild = current.children.find { it is ParamNode }
            if (paramChild != null) {
                params[(paramChild as ParamNode).paramName] = segment
                current = paramChild
                segmentIndex++
                continue
            }

            // 3. Finally fall back to a wildcard, which consumes the rest of the path.
            val wildcardChild = current.children.find { it is WildcardNode }
            if (wildcardChild != null) {
                val remainingSegments = segments.subList(segmentIndex, segments.size)
                params[(wildcardChild as WildcardNode).paramName] = remainingSegments.joinToString("/")
                current = wildcardChild
                break
            }

            return null
        }
```

(Leave the final `return current.children.find { it is RouteNode }?.let { (it as RouteNode).route to params }` line unchanged.)

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd /expanse/code/startup/zoned && ./gradlew --no-daemon jsNodeTest --tests 'zoned.framework.routing.RouteTrieTest'`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
cd /expanse/code/startup/zoned
git add build.gradle.kts src/jsMain/kotlin/zoned/framework/routing/RouteTrie.kt src/jsTest/kotlin/zoned/framework/routing/RouteTrieTest.kt
git commit -m "Match static routes before wildcard in RouteTrie"
```

---

### Task 2: Apply `RouteMetadata.title` to `document.title`

**Files:**
- Modify: `/expanse/code/startup/zoned/src/jsMain/kotlin/zoned/framework/routing/Router.kt`
- Modify: `/expanse/code/startup/zoned/src/jsTest/kotlin/zoned/framework/routing/RouteTrieTest.kt`

- [ ] **Step 1: Write the failing title-metadata test**

Add to `RouteTrieTest.kt`:

```kotlin
    @Test
    fun `title metadata computes document title from params`() {
        val route = RouteCreator.addRoute("/help/{topic}", handler = noop())
            .title { p -> "Help: ${p["topic"]}" }
        val title = route.metadata.title
        assertNotNull(title)
        assertEquals("Help: shortcuts", title(Params(mapOf("topic" to "shortcuts"))))
    }
```

- [ ] **Step 2: Run it to verify it passes already (plumbing check)**

Run: `cd /expanse/code/startup/zoned && ./gradlew --no-daemon jsNodeTest --tests 'zoned.framework.routing.RouteTrieTest'`
Expected: PASS — `.title { }` already stores the function in `metadata`. This test pins the contract that the Router will consume in Step 3. (The Router's actual `document.title` assignment depends on `window.location`/`document` and is verified manually in Phase E.)

- [ ] **Step 3: Apply the title in `Router.handleRoute`**

In `Router.kt`, inside `handleRoute`, after the `when (route.mode) { ... }` block and before `console.log("Navigating to ...")`, add:

```kotlin
            route.metadata.title?.let { document.title = it(Params(params)) }
```

(`document` and `Params` are already imported/used in this file.)

- [ ] **Step 4: Verify the module still compiles**

Run: `cd /expanse/code/startup/zoned && ./gradlew --no-daemon compileKotlinJs`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /expanse/code/startup/zoned
git add src/jsMain/kotlin/zoned/framework/routing/Router.kt src/jsTest/kotlin/zoned/framework/routing/RouteTrieTest.kt
git commit -m "Apply route title metadata to document.title"
```

---

### Task 3: Publish zoned to Maven Local

**Files:** none (build/publish only)

- [ ] **Step 1: Publish the framework library to `~/.m2`**

Run: `cd /expanse/code/startup/zoned && ./gradlew --no-daemon publishToMavenLocal`
Expected: BUILD SUCCESSFUL. Publishes `io.4rc:zoned-js:1.0-SNAPSHOT` (and `-jvm`).

If that fails because zoned's root build applies its own (stale) plugin, mirror the CI dance:

```bash
cd /expanse/code/startup/zoned
sed -i 's#id("io.4rc.zoned.plugin")#// id("io.4rc.zoned.plugin")#' build.gradle.kts
./gradlew --no-daemon :gradle-plugin:publishToMavenLocal
git checkout -- build.gradle.kts
./gradlew --no-daemon publishToMavenLocal
```

- [ ] **Step 2: Confirm the app resolves the new artifact**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon compileKotlinJs`
Expected: BUILD SUCCESSFUL (the app still builds against the updated framework; no source changes yet).

No commit (publish only).

---

## Phase B — app foundation (Layer 1)

### Task 4: `canvasUrl` helpers + idempotent canvas route

**Files:**
- Modify: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/Index.kt`
- Create: `/expanse/code/startup/thedozone2/src/jsTest/kotlin/tdz/CanvasUrlTest.kt`

- [ ] **Step 1: Write the failing URL-builder test**

Create `/expanse/code/startup/thedozone2/src/jsTest/kotlin/tdz/CanvasUrlTest.kt`:

```kotlin
package tdz

import tdz.model.CanvasName
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasUrlTest {
    @Test
    fun `empty path is root`() {
        assertEquals("/", canvasUrlFromPath(emptyList()))
    }

    @Test
    fun `segments join normalized with slashes`() {
        // "a" and "b" are already normalized, so they pass through unchanged.
        assertEquals("/a/b", canvasUrlFromPath(listOf(CanvasName("a"), CanvasName("b"))))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon compileTestKotlinJs`
Expected: FAIL — `canvasUrlFromPath` is unresolved.

- [ ] **Step 3: Add the URL builders to `Index.kt`**

In `Index.kt`, add a top-level function (outside the class, e.g. just above `class Index`):

```kotlin
/** Build a canvas URL from a resolved hierarchy path. Pure; unit-tested. */
fun canvasUrlFromPath(path: List<CanvasName>): String =
    if (path.isEmpty()) "/" else "/" + path.joinToString("/") { it.normalized() }
```

Inside `class Index`, add:

```kotlin
    fun canvasUrl(name: CanvasName): String = canvasUrlFromPath(model.path(name))
    fun currentCanvasUrl(): String = canvasUrl(model.currentCanvas().name)
```

- [ ] **Step 4: DRY the navigation handler to use `canvasUrl`**

In `Index.kt` `setupNavigationHandler`, replace these two lines:

```kotlin
            val path = model.path(canvasName)
            val url = "/" + path.joinToString("/") { it.normalized() }
            Router.navigate(url)
```

with:

```kotlin
            Router.navigate(canvasUrl(canvasName))
```

- [ ] **Step 5: Make `index()` idempotent and overlay-clearing**

In `Index.kt`, in `fun TagConsumer<*>.index(canvas: Canvas)`, replace the section from `currentCanvas = canvas` through the `getHtmlElement("app-container")?.remove()` line with:

```kotlin
        // Returning to a canvas route always dismisses any open overlay/modal layered on top.
        tdz.view.Overlay.clear()
        tdz.sync.SyncBootstrap.dismissModals()

        // Idempotent: if this exact canvas is already mounted, don't tear down and rebuild the
        // board (that would lose scroll/focus). Opening/closing an overlay reuses the live board.
        val existing = getHtmlElement("app-container")
        if (existing != null && currentCanvas?.name == canvas.name) {
            return
        }

        currentCanvas = canvas

        // The canvas route is PARTIAL (zoned appends rather than clearing the body), so remove any
        // existing canvas first — otherwise navigating stacks the new canvas below the old one.
        getHtmlElement("app-container")?.remove()
```

(Leave the body-styling block above it and the `div { id = "app-container" ... }` block below it unchanged. `Overlay` and `SyncBootstrap.dismissModals` are created in Task 5 and Task 8 — this file won't compile until those exist; that's fine for a TDD-by-task flow, but if you build now, expect unresolved references. Proceed to Task 5 next.)

- [ ] **Step 6: Run the URL test to verify it passes**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon jsBrowserTest --tests 'tdz.CanvasUrlTest'`
Expected: PASS (2 tests). If `/a/b` fails, check `CanvasName.normalized()` — adjust the test's expected value to match its actual transform, keeping the structural assertion (leading `/`, `/`-joined).

- [ ] **Step 7: Commit**

```bash
cd /expanse/code/startup/thedozone2
git add src/jsMain/kotlin/tdz/Index.kt src/jsTest/kotlin/tdz/CanvasUrlTest.kt
git commit -m "Add canvasUrl helpers and make canvas route idempotent"
```

---

### Task 5: Overlay layer + Escape handling

**Files:**
- Create: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/view/Overlay.kt`
- Modify: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/Index.kt`

- [ ] **Step 1: Create the `Overlay` object**

Create `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/view/Overlay.kt`:

```kotlin
package tdz.view

import kotlinx.browser.document
import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import tdz.App
import tdz.view.theme.ThemeManager
import web.html.HTMLElement
import zoned.framework.dom.Ref
import zoned.framework.dom.ref
import zoned.framework.interop.addToBody
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement
import zoned.framework.routing.Router

/**
 * A single full-screen overlay layer ("#overlay") that renders ON TOP of the live canvas
 * (#app-container) without tearing it down. Overlay routes (help, settings) render into it.
 * Closing navigates back to the current canvas URL; the (idempotent) canvas route then clears
 * this layer and leaves the board untouched.
 */
object Overlay {
    const val ID = "overlay"

    fun clear() {
        getHtmlElement(ID)?.remove()
    }

    fun close() {
        Router.navigate(App.index.resource.currentCanvasUrl())
    }

    fun show(title: String, content: FlowContent.() -> Unit) {
        clear()
        val theme = ThemeManager.current
        val scrim = Ref<HTMLElement>()
        addToBody {
            div {
                id = ID
                ref(scrim)
                css {
                    position = Position.fixed
                    top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                    zIndex = 10000
                    display = Display.flex
                    alignItems = Align.center
                    justifyContent = JustifyContent.center
                    backgroundColor = Color("rgba(0,0,0,0.45)")
                    padding = Padding(24.px)
                    boxSizing = BoxSizing.borderBox
                }
                onClick { e -> if (e.target == scrim.element) close() }

                div { // panel
                    css {
                        maxWidth = 760.px
                        width = 100.pct
                        maxHeight = 85.pct
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        backgroundColor = theme.appBg
                        color = theme.fg
                        fontFamily = theme.fontFamily
                        border = Border(1.px, BorderStyle.solid, theme.fg)
                        borderRadius = 8.px
                        overflow = Overflow.hidden
                    }
                    div { // header
                        css {
                            display = Display.flex
                            alignItems = Align.center
                            justifyContent = JustifyContent.spaceBetween
                            padding = Padding(12.px, 16.px)
                            borderBottom = Border(1.px, BorderStyle.solid, theme.fg)
                        }
                        div { +title }
                        button {
                            +"✕"
                            css {
                                backgroundColor = Color.transparent
                                color = theme.fg
                                borderStyle = BorderStyle.none
                                cursor = Cursor.pointer
                                fontSize = 16.px
                            }
                            onClick { close() }
                        }
                    }
                    div { // scrollable body
                        css {
                            padding = Padding(16.px)
                            overflowY = Overflow.auto
                        }
                        content()
                    }
                }
            }
        }
    }
}
```

> Note: this mirrors the scrim/panel pattern already used in `SyncModal.kt` (typed `onClick { e -> ... }` DSL from the kotlinx.html fork, `addToBody`, `Ref`/`ref`, `css { }`). If any import path differs, copy the exact imports from `SyncModal.kt`.

- [ ] **Step 2: Add Escape-to-close in `Index` keyboard handler**

In `Index.kt` `setupKeyboardHandlers`, add this branch in the `if/else if` chain (e.g. after the `F2` branch, before `if (handled)`):

```kotlin
            // Escape closes an open overlay route (help/settings) by navigating back to the canvas.
            else if (keyEvent.key == "Escape") {
                val p = kotlinx.browser.window.location.pathname
                if (p.startsWith("/help") || p.startsWith("/settings")) {
                    Router.navigate(currentCanvasUrl())
                    handled = true
                }
            }
```

- [ ] **Step 3: Verify it compiles (Overlay resolves in Index)**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon compileKotlinJs`
Expected: `Overlay.clear()` now resolves. `SyncBootstrap.dismissModals()` is still unresolved until Task 8 — if so, temporarily expect that one error and resolve it in Task 8, or implement Task 8's `dismissModals()` stub first. To keep each task independently compilable, do Step 4 now.

- [ ] **Step 4: Add a `dismissModals()` stub to `SyncBootstrap`**

In `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/sync/SyncBootstrap.kt`, add inside the `object SyncBootstrap`:

```kotlin
    /** Hide any open sync modal — used by the canvas route when returning from a /settings route. */
    fun dismissModals() {
        if (::modal.isInitialized) modal.hide()
        if (::localFolderModal.isInitialized) localFolderModal.hide()
    }
```

- [ ] **Step 5: Compile clean**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon compileKotlinJs`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /expanse/code/startup/thedozone2
git add src/jsMain/kotlin/tdz/view/Overlay.kt src/jsMain/kotlin/tdz/Index.kt src/jsMain/kotlin/tdz/sync/SyncBootstrap.kt
git commit -m "Add overlay layer, Escape-to-close, and SyncBootstrap.dismissModals"
```

---

## Phase C — help as routes (Layer 2)

### Task 6: Help content as markdown

**Files:**
- Create: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/view/help/HelpContent.kt`
- Create: `/expanse/code/startup/thedozone2/src/jsTest/kotlin/tdz/view/HelpContentTest.kt`

- [ ] **Step 1: Write the failing parse test**

Create `/expanse/code/startup/thedozone2/src/jsTest/kotlin/tdz/view/HelpContentTest.kt`:

```kotlin
package tdz.view

import tdz.model.markdown.MarkdownService
import tdz.view.help.HelpTopic
import kotlin.test.Test
import kotlin.test.assertTrue

class HelpContentTest {
    @Test
    fun `every help topic parses to at least one card`() {
        HelpTopic.values().forEach { topic ->
            val cards = MarkdownService.fromMd(topic.markdown).flatten()
            assertTrue(cards.isNotEmpty(), "${topic.name} produced no cards")
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon compileTestKotlinJs`
Expected: FAIL — `HelpTopic` unresolved.

- [ ] **Step 3: Create `HelpContent.kt` by converting the existing docs**

Create `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/view/help/HelpContent.kt`. Convert the three existing HTML docs faithfully into **card markdown** (the format the app's `MarkdownReader` understands: `#`-prefixed headings start a card; `- ` lines are list items). The source HTML uses tables, which the card format does not support — map each `<h2>` to a `# Heading` and each table row to a `- ` list item combining the action and its keys.

Source files to convert (preserve every entry):
- `src/jsMain/resources/docs/keyboard-shortcuts.html` → `SHORTCUTS`
- `src/jsMain/resources/docs/card-formatting.html` → `FORMATTING`
- `src/jsMain/resources/docs/release-notes.html` → `RELEASE_NOTES`

Conversion example (from the `Boards & Columns` table in `keyboard-shortcuts.html`):

```kotlin
package tdz.view.help

object HelpContent {
    // Converted from src/jsMain/resources/docs/keyboard-shortcuts.html (preserve all rows).
    val SHORTCUTS = """
        # Boards & Columns
        - Toggle the focused column between Card and Editor — Ctrl+' (or i) / ⌘+' (or i)
        - Focus the previous / next column — ← / →
        - Focus the column above / below — ↑ / ↓
        - Clear completed tasks on the board — Ctrl+; / ⌘+;
        - Split column, side by side — Ctrl+Alt+← / → (⌘+⌥+← / →)
        - Split column, stacked — Ctrl+Alt+↑ / ↓ (⌘+⌥+↑ / ↓)
        # Help
        - Keyboard Shortcuts — F1
        - Card Formatting — F2
    """.trimIndent()

    // Converted from src/jsMain/resources/docs/card-formatting.html (preserve all sections).
    val FORMATTING = """
        # Card Formatting
        - (convert every section/row from card-formatting.html here)
    """.trimIndent()

    // Converted from src/jsMain/resources/docs/release-notes.html (preserve all entries).
    val RELEASE_NOTES = """
        # Release Notes
        - (convert every entry from release-notes.html here)
    """.trimIndent()
}

enum class HelpTopic(val title: String, val markdown: String) {
    SHORTCUTS("Keyboard Shortcuts", HelpContent.SHORTCUTS),
    FORMATTING("Card Formatting", HelpContent.FORMATTING),
    RELEASE_NOTES("Release Notes", HelpContent.RELEASE_NOTES),
}
```

Replace the `(convert ...)` placeholders with the real converted content from the named source files before moving on — do not leave them.

- [ ] **Step 4: Run the parse test to verify it passes**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon jsBrowserTest --tests 'tdz.view.HelpContentTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /expanse/code/startup/thedozone2
git add src/jsMain/kotlin/tdz/view/help/HelpContent.kt src/jsTest/kotlin/tdz/view/HelpContentTest.kt
git commit -m "Add help content as card markdown"
```

---

### Task 7: Help routes + rewire entry points + delete static docs

**Files:**
- Modify: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/Index.kt`
- Modify: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/Main.kt`
- Modify: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/view/CanvasContextMenu.kt`
- Delete: `src/jsMain/resources/docs/*.html`, `docs.css`

- [ ] **Step 1: Add `openHelp` + `ensureCanvasMounted` to `Index`**

In `Index.kt`, add inside `class Index`:

```kotlin
    /** Render the current canvas into the body if nothing is mounted (e.g. cold deep-link). */
    private fun ensureCanvasMounted() {
        if (getHtmlElement("app-container") == null) {
            addToBody { index(model.currentCanvas()) }
        }
    }

    fun openHelp(topic: tdz.view.help.HelpTopic) {
        ensureCanvasMounted()
        tdz.view.Overlay.show(topic.title) {
            with(tdz.view.CardRenderer) {
                render(CanvasName("_help"), tdz.model.markdown.MarkdownService.fromMd(topic.markdown).flatten(), eventBus)
            }
        }
    }
```

Add the import at the top of `Index.kt`:

```kotlin
import zoned.framework.interop.addToBody
```

> `CardRenderer.render` is a `FlowContent` extension on the `object CardRenderer`; the `with(CardRenderer) { render(...) }` form brings it into scope inside the `Overlay.show` panel (which is a `FlowContent`). If the codebase calls it differently elsewhere (check `CanvasColumns`/`ColumnContainer`), match that call style.

- [ ] **Step 2: Register the help routes in `Main.kt`**

In `Main.kt`, inside `object index: Routes<Index>(...)`, after the `canvas` route, add:

```kotlin
        val helpShortcuts = route(mode = RenderMode.PARTIAL) {
            "/help/shortcuts" to { openHelp(tdz.view.help.HelpTopic.SHORTCUTS) }
        }.title { "Keyboard Shortcuts" }

        val helpFormatting = route(mode = RenderMode.PARTIAL) {
            "/help/formatting" to { openHelp(tdz.view.help.HelpTopic.FORMATTING) }
        }.title { "Card Formatting" }

        val helpReleaseNotes = route(mode = RenderMode.PARTIAL) {
            "/help/release-notes" to { openHelp(tdz.view.help.HelpTopic.RELEASE_NOTES) }
        }.title { "Release Notes" }
```

- [ ] **Step 3: Rewire F1/F2 to navigate**

In `Index.kt` `setupKeyboardHandlers`, replace the F1/F2 branches:

```kotlin
            else if (keyEvent.key == "F1") {
                kotlinx.browser.window.open("/docs/keyboard-shortcuts.html", "_blank")
                handled = true
            }
            else if (keyEvent.key == "F2") {
                kotlinx.browser.window.open("/docs/card-formatting.html", "_blank")
                handled = true
            }
```

with:

```kotlin
            else if (keyEvent.key == "F1") {
                Router.navigate("/help/shortcuts")
                handled = true
            }
            else if (keyEvent.key == "F2") {
                Router.navigate("/help/formatting")
                handled = true
            }
```

- [ ] **Step 4: Rewire the context-menu help entries**

In `CanvasContextMenu.kt`, change the three menu items (around lines 119-122) to pass the new route paths:

```kotlin
                    MenuItem(label = "Release Notes",
                        onSelect = { actions.openDoc("/help/release-notes") }),
                    MenuItem(label = "Keyboard Shortcuts", shortcut = "F1",
                        onSelect = { actions.openDoc("/help/shortcuts") }),
                    MenuItem(label = "Card Formatting", shortcut = "F2",
                        onSelect = { actions.openDoc("/help/formatting") }),
```

and change the `openDoc` implementation (around line 177) from `window.open` to navigation:

```kotlin
        override fun openDoc(path: String) { zoned.framework.routing.Router.navigate(path) }
```

(Update the `openDoc` KDoc comment on the interface at ~line 36 to say it navigates to an in-app help route.)

- [ ] **Step 5: Delete the static docs**

First confirm nothing else references them:

Run: `cd /expanse/code/startup/thedozone2 && grep -rn "docs/.*\.html\|docs\.css\|docs/keyboard\|docs/card-formatting\|docs/release" src/ || echo "no remaining references"`
Expected: no remaining references (besides anything you just edited).

Then remove:

```bash
cd /expanse/code/startup/thedozone2
git rm src/jsMain/resources/docs/keyboard-shortcuts.html src/jsMain/resources/docs/card-formatting.html src/jsMain/resources/docs/release-notes.html src/jsMain/resources/docs/docs.css
```

- [ ] **Step 6: Compile**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon compileKotlinJs`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd /expanse/code/startup/thedozone2
git add -A
git commit -m "Route help docs in-app and remove static HTML docs"
```

---

## Phase D — sync settings as routes (Layer 3)

### Task 8: Make sync modals dismiss via callback

**Files:**
- Modify: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/view/sync/SyncModal.kt`
- Modify: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/view/sync/LocalFolderModal.kt`

- [ ] **Step 1: Add `onDismiss` to `SyncModal`**

In `SyncModal.kt`, add a constructor parameter:

```kotlin
class SyncModal(
    private val model: Model,
    private val eventBus: EventBus,
    private val controller: SyncController,
    private val onDismiss: (() -> Unit)? = null,
) {
```

Then change the scrim click handler (the `onClick { e -> if (e.target == r.element) hide() }` line) to:

```kotlin
                onClick { e -> if (e.target == r.element) (onDismiss ?: { hide() })() }
```

(Leave `render()`'s internal `hide()` call and the public `hide()` unchanged — only the user-initiated scrim dismiss is redirected.)

- [ ] **Step 2: Add `onDismiss` to `LocalFolderModal`**

Apply the identical change in `LocalFolderModal.kt`: add the `onDismiss: (() -> Unit)? = null` constructor param and change its scrim `onClick { ... hide() }` to `(onDismiss ?: { hide() })()`.

- [ ] **Step 3: Compile**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon compileKotlinJs`
Expected: BUILD SUCCESSFUL (existing constructions still compile — `onDismiss` defaults to `null`).

- [ ] **Step 4: Commit**

```bash
cd /expanse/code/startup/thedozone2
git add src/jsMain/kotlin/tdz/view/sync/SyncModal.kt src/jsMain/kotlin/tdz/view/sync/LocalFolderModal.kt
git commit -m "Allow sync modals to dismiss via callback"
```

---

### Task 9: Settings routes + rewire open sites + init ordering

**Files:**
- Modify: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/Index.kt`
- Modify: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/Main.kt`
- Modify: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/sync/SyncBootstrap.kt`
- Modify: `/expanse/code/startup/thedozone2/src/jsMain/kotlin/tdz/view/CanvasContextMenu.kt`

- [ ] **Step 1: Add settings openers to `Index`**

In `Index.kt`, add inside `class Index`:

```kotlin
    fun openSyncSettings() {
        ensureCanvasMounted()
        tdz.sync.SyncBootstrap.modal.show()
    }

    fun openFolderSettings() {
        ensureCanvasMounted()
        tdz.sync.SyncBootstrap.localFolderModal.show()
    }
```

- [ ] **Step 2: Register the settings routes in `Main.kt`**

In `Main.kt`, inside `object index`, after the help routes, add:

```kotlin
        val settingsSync = route(mode = RenderMode.PARTIAL) {
            "/settings/sync" to { openSyncSettings() }
        }.title { "Cloud Sync" }

        val settingsFolder = route(mode = RenderMode.PARTIAL) {
            "/settings/folder" to { openFolderSettings() }
        }.title { "Local Folder Sync" }
```

- [ ] **Step 3: Construct the modals with a navigating `onDismiss`, and route Ctrl+Shift+D**

In `SyncBootstrap.kt` `init`, change the modal construction and the Ctrl+Shift+D handler:

```kotlin
        controller = SyncController(model, eventBus)
        val dismiss: () -> Unit = { zoned.framework.routing.Router.navigate(tdz.App.index.resource.currentCanvasUrl()) }
        modal = SyncModal(model, eventBus, controller, onDismiss = dismiss)
        localFolderModal = LocalFolderModal(model, eventBus, controller, onDismiss = dismiss)
        SyncIndicator(eventBus) { zoned.framework.routing.Router.navigate("/settings/sync") }.attach()

        document.addEventListener("keydown", { event ->
            val e = event as KeyboardEvent
            if (e.ctrlKey && e.shiftKey && (e.key == "D" || e.key == "d")) {
                e.preventDefault()
                zoned.framework.routing.Router.navigate("/settings/sync")
            }
        })
```

- [ ] **Step 4: Route the context-menu sync entries**

In `CanvasContextMenu.kt`, change the sync overrides (around lines 166-167):

```kotlin
        override fun openCloudSync() { zoned.framework.routing.Router.navigate("/settings/sync") }
        override fun openLocalFolderSync() { zoned.framework.routing.Router.navigate("/settings/folder") }
```

- [ ] **Step 5: Reorder `main()` so sync is initialized before routing starts**

In `Main.kt` `fun main()`, change the tail so `SyncBootstrap.init` runs before `Router.start()` (so a cold deep-link to `/settings/*` finds the modals initialized):

```kotlin
    App()
    tdz.sync.SyncBootstrap.init(tdzModel, eventBus)
    Router.start()
```

- [ ] **Step 6: Compile**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon compileKotlinJs`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd /expanse/code/startup/thedozone2
git add src/jsMain/kotlin/tdz/Index.kt src/jsMain/kotlin/tdz/Main.kt src/jsMain/kotlin/tdz/sync/SyncBootstrap.kt src/jsMain/kotlin/tdz/view/CanvasContextMenu.kt
git commit -m "Route cloud and local-folder sync settings"
```

---

## Phase E — verification

### Task 10: Full build, test, and manual verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full app build + tests (matches CI)**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon build-style build jsBrowserDistribution`
Expected: BUILD SUCCESSFUL, all `jsBrowserTest` tests pass (including `CanvasUrlTest`, `HelpContentTest`).

- [ ] **Step 2: Run the zoned framework tests**

Run: `cd /expanse/code/startup/zoned && ./gradlew --no-daemon jsNodeTest`
Expected: BUILD SUCCESSFUL (`RouteTrieTest` passes).

- [ ] **Step 3: Manual verification in the dev server**

Run: `cd /expanse/code/startup/thedozone2 && ./gradlew --no-daemon jsBrowserDevelopmentRun` (serves on port 3000), then in a browser verify each:

- [ ] Press **F1** → keyboard-shortcuts overlay appears *in-app* (not a new tab), themed; URL is `/help/shortcuts`; tab title is "Keyboard Shortcuts".
- [ ] Press **F2** → card-formatting overlay; URL `/help/formatting`.
- [ ] Context menu → **Release Notes** → overlay; URL `/help/release-notes`.
- [ ] **Esc** and the **✕** button and **clicking the scrim** each close the overlay and return to the canvas URL.
- [ ] **Browser back** from an open help overlay closes it and returns to the canvas.
- [ ] Scroll/focus a column, open help, close it → the board is **unchanged** (not rebuilt).
- [ ] Open a **cold deep link**: load `http://localhost:3000/help/shortcuts` directly → canvas renders underneath, help overlay on top.
- [ ] **Cloud Sync**: sync indicator badge, Ctrl+Shift+D, and context-menu "Cloud Sync" all navigate to `/settings/sync` and open the sync modal; closing returns to the canvas; reloading on `/settings/sync` reopens it.
- [ ] **Local Folder Sync** via context menu → `/settings/folder`, modal opens.
- [ ] A normal canvas with a name that is **not** `help`/`settings` still loads via its URL (e.g. `/my/board`), and switching canvases still works.

- [ ] **Step 4: Finalize**

If all checks pass, the branch is ready. Use the `superpowers:finishing-a-development-branch` skill to decide on merge/PR.

---

## Notes & known limitations (from the spec)

- `help` and `settings` are now **reserved** top-level path segments; a canvas literally named `help`/`settings` would be shadowed. Documented tradeoff; trie backtracking to remove it is a deferred follow-up.
- `FragmentRoute`/`fragment()` remains **unused/unfinished** — deliberately not wired up (its parent-re-render model fights the stateful board).
- Column properties and per-column CARD/EDITOR mode stay **non-routed** — the canvas is a terminal wildcard, so nested-under-canvas routes are structurally impossible.
- Reverse routing (`Route.path(map)`) and typed params (`Params.int/long/boolean`) are not adopted here; they're opportunistic and nothing in this scope needs them.
