package tdz

import kotlinx.browser.document
import kotlinx.css.*
import kotlinx.html.TagConsumer
import kotlinx.html.div
import kotlinx.html.id
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
import tdz.view.CanvasColumns
import tdz.view.Mode
import tdz.view.canvasMenu
import tdz.view.showCanvasSwitcher
import tdz.view.menu.attachContextMenu
import org.w3c.dom.events.KeyboardEvent
import web.html.HTMLElement
import tdz.view.theme.ThemeManager
import zoned.framework.interop.classes
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement
import zoned.framework.interop.clear
import zoned.framework.interop.appendTo
import zoned.framework.interop.addToBody
import zoned.framework.routing.Router

/** Build a canvas URL from a resolved hierarchy path. Pure; unit-tested. */
fun canvasUrlFromPath(path: List<CanvasName>): String =
    if (path.isEmpty()) "/" else "/" + path.joinToString("/") { it.normalized() }

class Index(val model: Model, val eventBus: EventBus) {

    fun canvasUrl(name: CanvasName): String = canvasUrlFromPath(model.path(name))
    fun currentCanvasUrl(): String = canvasUrl(model.currentCanvas().name)

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

    private var currentColumns: CanvasColumns? = null
    private val downloader = Downloader(model)
    private val uploader = Uploader(model, eventBus)
    private var currentCanvas: Canvas? = null

    val canvas: Canvas? get() = currentCanvas

    init {
        setupKeyboardHandlers()
        setupNavigationHandler()
        setupEditorHandlers()
        setupRefreshHandler()
    }

    /** Editor-originated keys (Alt+arrow, Esc) reach the column model via events. */
    private fun setupEditorHandlers() {
        eventBus.on<NavigateColumn> { event ->
            currentColumns?.navigateFocus(event.direction)
            currentColumns?.focusEditor()
        }
        eventBus.on<ExitEditor> { event ->
            currentColumns?.let { if (it.modeOf(event.columnId) == Mode.EDITOR) it.toggleMode(event.columnId) }
        }
    }

    /** Re-render the whole current canvas in place (e.g. after a layout split). */
    private fun setupRefreshHandler() {
        eventBus.on<RefreshCanvas> {
            (document.body as? HTMLElement)?.let { body ->
                body.clear()
                body.appendTo().apply { index(model.currentCanvas()) }
            }
        }
    }

    private fun setupNavigationHandler() {
        eventBus.on<SwitchToCanvas> { event ->
            val canvasName = event.canvasName

            // Create canvas if it doesn't exist
            if (!model.exists(canvasName)) {
                val parentCanvas = currentCanvas?.name ?: model.currentCanvas().name
                model.addCanvas(canvasFromTemplate(canvasName, parentCanvas, SINGLE_PANE))
            }

            // Change current canvas in model
            model.changeCanvas(canvasName)

            // Update URL with hierarchical path
            Router.navigate(canvasUrl(canvasName))
        }
    }

    private fun setupKeyboardHandlers() {
        document.addEventListener("keydown", { event ->
            val keyEvent = event as KeyboardEvent
            var handled = false

            // Ctrl+' toggles editor mode for focused column
            if (keyEvent.ctrlKey && keyEvent.key == "'") {
                currentColumns?.toggleMode()
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
                    currentColumns?.navigateFocus(it)
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
                val columns = currentColumns
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
                Router.navigate("/help/shortcuts")
                handled = true
            }
            else if (keyEvent.key == "F2") {
                Router.navigate("/help/formatting")
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
                event.preventDefault()
            }
        })
    }

    fun TagConsumer<*>.index(canvas: Canvas) {
        (document.body as? HTMLElement)?.let { body ->
            body.classes("min-h-screen")
            val theme = ThemeManager.current
            body.css {
                backgroundColor = theme.appBg
                // gradient / DOS dither / rainbow — the full background-image lives on the theme
                backgroundImage = Image(theme.appBgImage)
                color = theme.fg
                fontFamily = theme.fontFamily
                fontSize = theme.baseFontSize
            }
        }
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

        div {
            id = "app-container"
            // columnGap also pads the screen edge, so panels gap away from the window border too
            css {
                width = 100.vw
                height = 100.vh
                overflow = Overflow.hidden
                boxSizing = BoxSizing.borderBox
                padding = Padding(ThemeManager.current.columnGap)
            }
            val columns = CanvasColumns(canvas, eventBus, model, { id -> model.cards(canvas.name, id) })
            currentColumns = columns
            attachContextMenu(canvasMenu(columns, model, eventBus, downloader, uploader))
            with(columns) {
                this@div.render(canvas.columns)
            }
        }
    }
}

