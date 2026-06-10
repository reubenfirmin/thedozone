package tdz

import kotlinx.css.*
import kotlinx.html.TagConsumer
import kotlinx.html.div
import tdz.controller.Controller
import tdz.controller.Downloader
import tdz.controller.Uploader
import tdz.controller.events.ClearDone
import tdz.controller.events.EventBus
import tdz.controller.events.ExitEditor
import tdz.controller.events.NavigateColumn
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.SplitColumn
import tdz.controller.events.SwitchToCanvas
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.Direction
import tdz.model.Split
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.markdown.MarkdownService
import tdz.helpPages
import tdz.sync.SyncBootstrap
import tdz.view.CanvasColumns
import tdz.view.CanvasView
import tdz.view.Mode
import tdz.view.ZLayer
import tdz.view.Zones
import tdz.view.canvasMenu
import tdz.view.CardRenderer
import tdz.view.Overlay
import tdz.view.help.HelpTopic
import tdz.view.overlayPanel
import tdz.view.showCanvasSwitcher
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
import zoned.framework.interop.styleSheet
import zoned.framework.routing.Router
import zoned.framework.routing.Zone.Companion.zone
import zoned.framework.dom.onClick

/** Build a canvas URL from a resolved hierarchy path. Pure; unit-tested. */
fun canvasUrlFromPath(path: List<CanvasName>): String =
    if (path.isEmpty()) "/" else "/" + path.joinToString("/") { it.normalized() }

class Index(val model: Model, val eventBus: EventBus) {

    fun canvasUrl(name: CanvasName): String = canvasUrlFromPath(model.path(name))
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
    private val helpEventBus = EventBus()

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
            index(model.currentCanvas())
        }
    }

    private fun setupNavigationHandler() {
        eventBus.on<SwitchToCanvas> { event ->
            val canvasName = event.canvasName

            // Create canvas if it doesn't exist
            if (!model.exists(canvasName)) {
                val parentCanvas = currentCanvasName ?: model.currentCanvas().name
                model.addCanvas(canvasFromTemplate(canvasName, parentCanvas, SINGLE_PANE))
            }

            // Change current canvas in model
            model.changeCanvas(canvasName)

            // Update URL with hierarchical path
            Router.navigate(canvasUrl(canvasName))
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
            else if (!keyEvent.ctrlKey && !keyEvent.altKey && keyEvent.key in listOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown")) {
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
            // Page Up goes to the parent board (if any); Page Down opens the child-board switcher.
            else if (keyEvent.key == "PageUp") {
                model.currentCanvas().parent?.let { eventBus.fire(SwitchToCanvas(it)) }
                handled = true
            }
            else if (keyEvent.key == "PageDown") {
                showCanvasSwitcher(model, eventBus)
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
            // Escape closes an open overlay route (help/settings) by navigating back to the canvas.
            else if (keyEvent.key == "Escape") {
                val p = kotlinx.browser.window.location.pathname
                if (p == "/help" || p.startsWith("/help/") || p == "/settings" || p.startsWith("/settings/")) {
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

    /**
     * Build the stable shell — the swap targets — once. Routes and refreshes swap zone CONTENTS
     * ([Zones.CANVAS], [Zones.OVERLAY]); the shell elements themselves (and every body-level
     * sibling layer) stay put for the life of the app.
     */
    private fun ensureShell() {
        if (Zones.CANVAS.resolve() != null) return
        addToBody {
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
        styleSheet("tdz-overlay-zone") {
            // !important: the zone's inline display:flex must lose when the zone is empty.
            rule("#${Zones.OVERLAY.elementId}:empty") { important("display", "none") }
        }
    }

    fun index(canvas: Canvas) {
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
        // Returning to a canvas route always dismisses any open overlay/modal layered on top.
        closeOverlays()
        ensureShell()

        // Idempotent: if this exact canvas is already mounted, don't tear down and rebuild the
        // board (that would lose scroll/focus). Opening/closing an overlay reuses the live board.
        if (currentCanvasName == canvas.name && canvasView.columns != null) {
            return
        }
        currentCanvasName = canvas.name

        // The shell is permanent, so its theme-dependent chrome is re-applied on every render.
        Zones.CANVAS.resolve()?.css {
            width = 100.vw
            height = 100.vh
            overflow = Overflow.hidden
            boxSizing = BoxSizing.borderBox
            // columnGap also pads the screen edge, so panels gap away from the window border too
            padding = Padding(ThemeManager.current.columnGap)
        }

        canvasView.swap(canvas)
    }
}

