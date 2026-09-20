package tdz

import kotlinx.html.TagConsumer
import tdz.controller.DragDrop
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
import tdz.helpPages
import tdz.legalPages
import tdz.settingsPages
import tdz.sync.SyncBootstrap
import tdz.view.AppShell
import tdz.view.CanvasView
import tdz.view.KeyboardShortcuts
import tdz.view.ResizeWatcher
import tdz.view.ShortcutActions
import tdz.view.Omnibox
import tdz.view.Mode
import tdz.view.Zones
import tdz.view.HelpView
import tdz.view.Overlay
import tdz.view.help.HelpTopic
import web.dom.document
import web.html.HTMLElement
import zoned.framework.routing.Router

/**
 * Thin render coordinator: it owns the [CanvasView] and the swap-in/swap-out of canvas renders, and
 * wires the document-level units ([KeyboardShortcuts], [ResizeWatcher], [AppShell]) plus the
 * event-bus handlers that turn controller events into renders/navigations. The heavy lifting lives
 * in those units; Index just holds them together.
 */
class Index(val model: Model, val eventBus: EventBus<Event>, dragDrop: DragDrop? = null) : ShortcutActions {

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

    private val helpView = HelpView()

    /** A help topic as read-only cards in the overlay panel (the content the help fragment routes
     *  swap into [Zones.OVERLAY]). Thin delegation to [HelpView], which owns the mechanism — kept
     *  here as the routes' entry point (the fragment lambdas resolve it as an Index member). */
    fun TagConsumer<HTMLElement>.helpPage(topic: HelpTopic) {
        with(helpView) { render(topic) }
    }

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

    private val canvasView = CanvasView(eventBus, model, dragDrop)
    private val shell = AppShell(model, eventBus) { canvasView.columns }

    /** Idempotency guard: the canvas currently rendered into the zone (policy state, not DOM state). */
    private var currentCanvasName: CanvasName? = null

    init {
        KeyboardShortcuts(this).install()
        setupNavigationHandler()
        setupEditorHandlers()
        setupRefreshHandler()
        // structural=false: a layout-mode flip is purely local UI and must not push to sync.
        ResizeWatcher { eventBus.fire(RefreshCanvas(model.currentCanvas().name, structural = false)) }.install()
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

    // --- ShortcutActions: keyboard bindings dispatch here (see [KeyboardShortcuts]) ---------------

    override fun toggleEditorMode(): Boolean {
        canvasView.columns?.toggleMode()
        return true
    }

    override fun navigateFocus(direction: Direction): Boolean {
        canvasView.columns?.navigateFocus(direction)
        return true
    }

    override fun splitFocusedColumn(split: Split): Boolean {
        val columns = canvasView.columns ?: return false
        eventBus.fire(SplitColumn(model.currentCanvas().name, split, columns.focused()))
        return true
    }

    override fun clearDone(): Boolean {
        eventBus.fire(ClearDone(model.currentCanvas().name))
        return true
    }

    override fun goToParentBoard(): Boolean {
        val current = model.currentCanvas().name
        if (!current.isRoot()) eventBus.fire(SwitchToCanvas(current.parentName()))
        return true
    }

    override fun openBoardPalette(): Boolean {
        Omnibox.show(model, eventBus)
        return true
    }

    override fun openHelpShortcuts(): Boolean {
        Router.navigate(helpPages.shortcuts.path())
        return true
    }

    override fun openHelpFormatting(): Boolean {
        Router.navigate(helpPages.formatting.path())
        return true
    }

    override fun closeOpenOverlayRoute(): Boolean {
        val p = kotlinx.browser.window.location.pathname
        fun isUnder(base: String) = p == base || p.startsWith("$base/")
        if (isUnder(helpPages.root.path()) || isUnder(settingsPages.root.path()) ||
            isUnder(legalPages.privacy.path())) {
            Router.navigate(currentCanvasUrl())
            return true
        }
        return false
    }

    /** Includes CodeMirror's contenteditable surface, so letter shortcuts never hijack typing. */
    override fun isEditableFocused(): Boolean {
        val ae = document.activeElement ?: return false
        val tag = ae.tagName.uppercase()
        if (tag == "INPUT" || tag == "TEXTAREA") return true
        return (ae as? HTMLElement)?.isContentEditable == true
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
        // Body + canvas-zone theming is shell chrome — AppShell owns it (view layer).
        shell.applyBodyTheme()
        // Returning to a canvas ROUTE always dismisses any open overlay/modal layered on top;
        // sync-triggered refreshes pass dismissOverlays = false (see setupRefreshHandler).
        if (dismissOverlays) closeOverlays()
        shell.ensure()
        shell.ensureTitleBarStyles()

        // Idempotent: if this exact canvas is already mounted, don't tear down and rebuild the
        // board (that would lose scroll/focus). Opening/closing an overlay reuses the live board.
        if (currentCanvasName == canvas.name && canvasView.columns != null) {
            return
        }
        currentCanvasName = canvas.name

        shell.applyCanvasZoneChrome()

        canvasView.swap(canvas)
    }
}
