package tdz

import kotlinx.css.*
import kotlinx.html.TagConsumer
import kotlinx.html.div
import tdz.controller.Controller
import tdz.controller.Downloader
import tdz.controller.Uploader
import tdz.controller.events.ClearDone
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.ExitEditor
import tdz.controller.events.NavigateColumn
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.SplitColumn
import tdz.controller.events.SwitchToCanvas
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.Direction
import tdz.model.Split
import tdz.model.Model
import tdz.model.markdown.MarkdownService
import tdz.helpPages
import tdz.legalPages
import tdz.settingsPages
import tdz.sync.SyncBootstrap
import tdz.view.CanvasColumns
import tdz.view.CanvasView
import tdz.view.Omnibox
import tdz.view.Mode
import tdz.view.ZLayer
import tdz.view.Zones
import tdz.view.canvasMenu
import tdz.view.CardRenderer
import tdz.view.Overlay
import tdz.view.help.HelpTopic
import tdz.view.overlayPanel
import tdz.view.menu.attachContextMenu
import web.dom.document
import web.events.EventType
import web.events.addEventListener
import web.html.HTMLElement
import web.keyboard.KeyboardEvent
import tdz.view.theme.ThemeManager
import zoned.framework.interop.css
import zoned.framework.interop.addToBody
import zoned.framework.interop.important
import tdz.view.theme.themedStyleSheet
import zoned.framework.routing.Router
import zoned.framework.routing.Zone.Companion.zone
import zoned.framework.dom.onClick

class Index(val model: Model, val eventBus: EventBus<Event>) {

    fun currentCanvasUrl(): String = canvasUrl(model.currentCanvas().name)

    /** Render the current canvas if nothing is mounted (e.g. cold deep-link to an overlay route). */
    private fun ensureCanvasMounted() {
        if (canvasView.columns == null || Zones.CANVAS.resolve() == null) {
            index(model.currentCanvas())
        }
    }

    /** Dismiss any overlay/modal layered on the canvas (the #overlay layer and the sync modals). */
    private fun closeOverlays() {
        Overlay.clear()
        SyncBootstrap.dismissModals()
    }

    /**
     * A help topic as read-only cards in the overlay panel — the content the help fragment routes
     * swap into [Zones.OVERLAY]. Uses [helpEventBus] (inert) so the help content's interactive
     * affordances never mutate real state. NOTE: help markdown must not use bare canvas-link
     * syntax outside code fences — CardRenderer's canvas-link onMount still registers a Sortable
     * drop zone regardless of the bus, so such links would be a latent trap.
     */
    fun TagConsumer<HTMLElement>.helpPage(topic: HelpTopic) {
        overlayPanel(topic.title) {
            with(CardRenderer) {
                render(CanvasName("_help"), MarkdownService.fromMd(topic.markdown).flatten(), helpEventBus, loose = true)
            }
        }
    }

    // Help content is read-only: render its cards through an inert bus with no subscribers, so
    // interactions (task-toggle, canvas-link navigation) fired by CardRenderer never reach the
    // live controller/model (which would mutate or throw against the synthetic "_help" canvas).
    private val helpEventBus = EventBus<Event>()

    fun openSyncSettings() {
        ensureCanvasMounted()
        closeOverlays()
        SyncBootstrap.modal.show()
    }

    fun openFolderSettings() {
        ensureCanvasMounted()
        closeOverlays()
        SyncBootstrap.localFolderModal.show()
    }

    private val canvasView = CanvasView(eventBus, model)
    private val downloader = Downloader(model)
    private val uploader = Uploader(model, eventBus)

    /** Idempotency guard: the canvas currently rendered into the zone (policy state, not DOM state). */
    private var currentCanvasName: CanvasName? = null

    init {
        setupKeyboardHandlers()
        setupNavigationHandler()
        setupEditorHandlers()
        setupRefreshHandler()
        setupResizeHandler()
    }

    /**
     * Re-render the board when the viewport crosses the mobile breakpoint (rotation, responsive
     * resize), so it flips between full-layout and single-column ([CanvasColumns] decides which).
     * Installed once for the app's life; debounced, and only fires on an actual flip. structural=false
     * because a layout-mode change is purely local UI and must not push to sync.
     */
    private fun setupResizeHandler() {
        var wasMobile = kotlinx.browser.window.innerWidth <= CanvasColumns.MOBILE_BREAKPOINT
        var debounce = 0
        kotlinx.browser.window.addEventListener("resize", {
            kotlinx.browser.window.clearTimeout(debounce)
            debounce = kotlinx.browser.window.setTimeout({
                val nowMobile = kotlinx.browser.window.innerWidth <= CanvasColumns.MOBILE_BREAKPOINT
                if (nowMobile != wasMobile) {
                    wasMobile = nowMobile
                    eventBus.fire(RefreshCanvas(model.currentCanvas().name, structural = false))
                }
            }, 150)
        })
    }

    /** Editor-originated keys (Alt+arrow, Esc) reach the column model via events. */
    private fun setupEditorHandlers() {
        eventBus.on<NavigateColumn> { event ->
            canvasView.columns?.navigateFocus(event.direction)
            canvasView.columns?.focusEditor()
        }
        eventBus.on<ExitEditor> { event ->
            canvasView.columns?.let { if (it.modeOf(event.columnId) == Mode.EDITOR) it.toggleMode(event.columnId) }
        }
    }

    /** Re-render the current canvas in place (e.g. after a layout split). */
    private fun setupRefreshHandler() {
        eventBus.on<RefreshCanvas> { event ->
            // A structural change to a canvas that ISN'T on screen (e.g. a wormhole move's target,
            // a pulled manifest for another board) is sync's business, not a render trigger —
            // rebuilding the current board for it would cost scroll/focus for nothing.
            if (event.canvasName != model.currentCanvas().name) return@on
            // Zone-granular: CanvasView.swap saves open editors and detaches the old render's
            // subscriptions; body-level siblings (badge, portals, overlays) are never torn down.
            currentCanvasName = null      // force past the idempotency guard
            // dismissOverlays = false: this is a data refresh, not a route change. A reconcile's
            // pulled manifest lands here, and dismissing the sync modal as a side effect strands
            // the URL at /settings/sync (the modal's own dismissal is what navigates away).
            index(model.currentCanvas(), dismissOverlays = false)
        }
    }

    /**
     * Navigation is a view concern: turn a [SwitchToCanvas] into a URL change. The board chain is
     * created and made current by the route's dispatch to [tdz.controller.CanvasHierarchy] when the
     * URL lands — Index does no model work here.
     */
    private fun setupNavigationHandler() {
        eventBus.on<SwitchToCanvas> { event ->
            Router.navigate(canvasUrl(event.canvasName))   // absolute path
        }
    }

    private fun setupKeyboardHandlers() {
        document.addEventListener(EventType<KeyboardEvent>("keydown"), { keyEvent ->
            var handled = false

            // Ctrl+' toggles editor mode for focused column
            if (keyEvent.ctrlKey && keyEvent.key == "'") {
                canvasView.columns?.toggleMode()
                handled = true
            }
            // Plain `i` is the documented alias for the toggle (vim-style "i" to start editing). Guarded
            // so it never hijacks typing: Ace lets plain-character keydowns bubble to the document (unlike
            // arrows, which it consumes), so without this guard `i` would toggle mid-edit. In card mode
            // nothing editable is focused, so `i` acts as the toggle; while editing it's a normal char.
            else if (!keyEvent.ctrlKey && !keyEvent.altKey && !keyEvent.metaKey && keyEvent.key == "i" && !isEditableFocused()) {
                canvasView.columns?.toggleMode()
                handled = true
            }
            // Arrow keys navigate between columns (without ctrl/alt modifiers)
            else if (!keyEvent.ctrlKey && !keyEvent.altKey && !keyEvent.metaKey && keyEvent.key in listOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown")) {
                val direction = when (keyEvent.key) {
                    "ArrowLeft" -> Direction.LEFT
                    "ArrowRight" -> Direction.RIGHT
                    "ArrowUp" -> Direction.UP
                    "ArrowDown" -> Direction.DOWN
                    else -> null
                }
                direction?.let {
                    canvasView.columns?.navigateFocus(it)
                    handled = true
                }
            }
            // Ctrl+Alt+arrow splits the focused column: left/right side by side, up/down stacked.
            else if (keyEvent.ctrlKey && keyEvent.altKey && keyEvent.key in listOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown")) {
                val split = when (keyEvent.key) {
                    "ArrowLeft", "ArrowRight" -> Split.VERTICAL    // side by side
                    "ArrowUp", "ArrowDown" -> Split.HORIZONTAL     // stacked
                    else -> null
                }
                val columns = canvasView.columns
                if (split != null && columns != null) {
                    eventBus.fire(SplitColumn(model.currentCanvas().name, split, columns.focused()))
                    handled = true
                }
            }
            // Ctrl+; clears completed tasks from every card on the canvas.
            else if (keyEvent.ctrlKey && !keyEvent.altKey && keyEvent.key == ";") {
                eventBus.fire(ClearDone(model.currentCanvas().name))
                handled = true
            }
            // Page Up jumps straight to the parent board (if any).
            else if (keyEvent.key == "PageUp") {
                val current = model.currentCanvas().name
                if (!current.isRoot()) eventBus.fire(SwitchToCanvas(current.parentName()))
                handled = true
            }
            // Page Down, Ctrl/Cmd+L, and Ctrl/Cmd+K all open the board palette — the single navigation
            // hub (type a path to go/create, or filter/arrow to the current board's children and all
            // boards). L restores URL-style addressing in the installed PWA window (no browser address
            // bar); K is the conventional command-palette key. Both are guarded by editable focus so
            // they never hijack Ace's in-editor Ctrl+L (go-to-line) / Ctrl+K (find-next).
            else if (keyEvent.key == "PageDown") {
                Omnibox.show(model, eventBus)
                handled = true
            }
            else if ((keyEvent.ctrlKey || keyEvent.metaKey) && keyEvent.key.lowercase() in listOf("l", "k") && !isEditableFocused()) {
                Omnibox.show(model, eventBus)
                handled = true
            }
            // F1 / F2 open the corresponding help docs as in-app overlay routes.
            else if (keyEvent.key == "F1") {
                Router.navigate(helpPages.shortcuts.path())
                handled = true
            }
            else if (keyEvent.key == "F2") {
                Router.navigate(helpPages.formatting.path())
                handled = true
            }
            // Escape closes an open overlay route (help/settings/privacy) by returning to the canvas.
            else if (keyEvent.key == "Escape") {
                val p = kotlinx.browser.window.location.pathname
                fun isUnder(base: String) = p == base || p.startsWith("$base/")
                if (isUnder(helpPages.root.path()) || isUnder(settingsPages.root.path()) ||
                    isUnder(legalPages.privacy.path())) {
                    Router.navigate(currentCanvasUrl())
                    handled = true
                }
            }

            if (handled) {
                keyEvent.preventDefault()
            }
        })
    }

    /** True when a text field or the Ace editor has focus, so a bare letter key must type, not act as a
     *  shortcut. Ace's input target is a textarea, so this also covers in-editor typing. */
    private fun isEditableFocused(): Boolean {
        val ae = document.activeElement ?: return false
        val tag = ae.tagName.uppercase()
        if (tag == "INPUT" || tag == "TEXTAREA") return true
        return (ae as? HTMLElement)?.isContentEditable == true
    }

    /** Themed CSS for the WCO titlebar strip. Re-ensured each render so it recolors on theme switch
     *  (themedStyleSheet no-ops unless the theme changed). Sized via the titlebar-area-* env vars
     *  (0 ⇒ invisible when WCO is inactive); transparent so the body's themed gradient shows through. */
    private fun ensureTitleBarStyles() {
        val t = ThemeManager.current
        themedStyleSheet("tdz-titlebar-styles") {
            rule(".tdz-titlebar") {
                position = Position.fixed
                put("left", "env(titlebar-area-x, 0px)")
                put("top", "env(titlebar-area-y, 0px)")
                put("width", "env(titlebar-area-width, 0px)")   // 0 ⇒ collapsed/invisible without WCO
                put("height", "env(titlebar-area-height, 0px)")
                zIndex = ZLayer.TITLEBAR
                display = Display.flex
                alignItems = Align.center
                paddingLeft = 12.px
                overflow = Overflow.hidden
                whiteSpace = WhiteSpace.nowrap
                color = t.menuFg
                fontFamily = t.fontFamily
                fontSize = 12.px
                fontWeight = FontWeight.w600
                letterSpacing = 0.04.em
                put("-webkit-app-region", "drag")   // the strip drags the OS window
                put("user-select", "none")
            }
        }
    }

    /**
     * Build the stable shell — the swap targets — once. Routes and refreshes swap zone CONTENTS
     * ([Zones.CANVAS], [Zones.OVERLAY]); the shell elements themselves (and every body-level
     * sibling layer) stay put for the life of the app.
     */
    private fun ensureShell() {
        if (Zones.CANVAS.resolve() != null) return
        addToBody {
            // Window Controls Overlay titlebar (installed PWA on desktop Chromium). Sized by the
            // titlebar-area-* env vars, which are 0 where WCO is inactive (browser tab / mobile /
            // unsupported), so it self-collapses to nothing there. Transparent — the body's themed
            // background/gradient shows straight through, so the strip always matches the theme;
            // draggable so it moves the OS window. Styles (themed text) in ensureTitleBarStyles().
            div("tdz-titlebar") { +"The Do Zone" }
            div {
                zone(Zones.CANVAS)
                // Resolve menu sections against the LIVE columns at right-click time, so the menu
                // never closes over a stale CanvasColumns after a canvas switch or refresh.
                attachContextMenu { event ->
                    canvasView.columns?.let { canvasMenu(it, model, eventBus, downloader, uploader)(event) }
                        ?: emptyList()
                }
            }
            // The overlay layer is the SCRIM itself: a permanent sibling the help fragments swap
            // panels into. Empty = invisible (the :empty rule below; !important beats inline flex).
            div {
                zone(Zones.OVERLAY)
                css {
                    position = Position.fixed
                    top = 0.px; left = 0.px; right = 0.px; bottom = 0.px
                    zIndex = ZLayer.OVERLAY
                    display = Display.flex
                    alignItems = Align.center
                    justifyContent = JustifyContent.center
                    backgroundColor = ThemeManager.current.scrimBg
                    padding = Padding(24.px)
                    boxSizing = BoxSizing.borderBox
                }
                onClick { e ->
                    // Only a click on the scrim itself closes; panel clicks bubble with an inner target.
                    if ((e.target as? web.dom.Element)?.id?.toString() == Zones.OVERLAY.elementId) {
                        Overlay.close()
                    }
                }
            }
        }
        themedStyleSheet("tdz-overlay-zone") {
            // !important: the zone's inline display:flex must lose when the zone is empty.
            rule("#${Zones.OVERLAY.elementId}:empty") { important("display", "none") }
        }
    }

    fun index(rawCanvas: Canvas, dismissOverlays: Boolean = true) {
        // Self-heal a corrupt/empty layout BEFORE rendering: a cyclic synced manifest, or a board
        // deleted down to zero columns before the delete-invariant existed, would otherwise render
        // nothing with no way to add a column. repaired() rebuilds a flat layout over the existing
        // content columns (preserving their cards) or one fresh empty column. Persist the repair so
        // it sticks and the valid manifest syncs out.
        val canvas = rawCanvas.repaired()
        if (canvas != rawCanvas) {
            console.warn("[canvas] repaired unsound layout for '${canvas.name.normalized()}' " +
                "(was: ${rawCanvas.layouts}); rebuilt as a flat single-row layout.")
            model.addCanvas(canvas)
            model.changeCanvas(canvas.name)
        }
        document.body.let { body ->
            val theme = ThemeManager.current
            body.css {
                minHeight = 100.vh
                backgroundColor = theme.appBg
                // gradient / DOS dither / rainbow — the full background-image lives on the theme
                backgroundImage = Image(theme.appBgImage)
                color = theme.fg
                fontFamily = theme.fontFamily
                fontSize = theme.baseFontSize
            }
        }
        // Returning to a canvas ROUTE always dismisses any open overlay/modal layered on top;
        // sync-triggered refreshes pass dismissOverlays = false (see setupRefreshHandler).
        if (dismissOverlays) closeOverlays()
        ensureShell()
        ensureTitleBarStyles()

        // Idempotent: if this exact canvas is already mounted, don't tear down and rebuild the
        // board (that would lose scroll/focus). Opening/closing an overlay reuses the live board.
        if (currentCanvasName == canvas.name && canvasView.columns != null) {
            return
        }
        currentCanvasName = canvas.name

        // The shell is permanent, so its theme-dependent chrome is re-applied on every render.
        Zones.CANVAS.resolve()?.css {
            width = 100.vw
            // Reserve the WCO titlebar strip so board content starts below it. env() is 0px when
            // not an installed PWA with an overlay, so this is a no-op in a browser tab / mobile.
            put("margin-top", "env(titlebar-area-height, 0px)")
            put("height", "calc(100vh - env(titlebar-area-height, 0px))")
            overflow = Overflow.hidden
            boxSizing = BoxSizing.borderBox
            // columnGap also pads the screen edge, so panels gap away from the window border too
            padding = Padding(ThemeManager.current.columnGap)
        }

        canvasView.swap(canvas)
    }
}

