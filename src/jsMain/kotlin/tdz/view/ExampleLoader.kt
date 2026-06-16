package tdz.view

import tdz.controller.events.Event
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.SwitchToCanvas
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.rootCanvas
import tdz.view.theme.Theme
import tdz.view.theme.ThemeManager
import zoned.framework.events.EventBus

/**
 * Applies example boards to the canvas and tears them down again. A scene is one or more boards: the
 * root board plus any named child boards (which it tracks, so [reset] can delete every board the
 * tour created — leaving the user a clean, empty root when they leave the wizard).
 *
 * Loads mirror the controller's layout-change path (overwrite the manifest, reload the current
 * canvas, re-render) so the board repaints from the new spec with the wizard overlay left intact.
 */
object ExampleLoader {

    /** Child boards (non-root) this loader has created, so they can be wiped on [reset]. */
    private val createdChildren = mutableSetOf<CanvasName>()

    /** The user's theme before an example previewed a different one — restored on [reset]. */
    private var savedTheme: Theme? = null

    /** Replace the root board with a single example (the step-one gallery picks). */
    fun load(model: Model, eventBus: EventBus<Event>, board: ExampleBoard) =
        loadScene(model, eventBus, listOf(board))

    /** Drop a whole scene (root + child boards) onto the canvas, then show the root. */
    fun loadScene(model: Model, eventBus: EventBus<Event>, boards: List<ExampleBoard>) {
        wipeChildren(model)                                          // clear a prior scene's children first
        clearRootContent(model)
        applyTheme(boards.firstNotNullOfOrNull { it.theme })         // preview the requested theme, if any
        boards.forEach { board ->
            model.addCanvas(board.canvas())                          // overwrite/create the manifest
            board.content.forEach { (col, md) -> model.updateColumn(board.name, col, md) }
            if (board.name != rootCanvas) createdChildren += board.name
        }
        model.changeCanvas(rootCanvas)
        eventBus.fire(RefreshCanvas(rootCanvas))
    }

    /** Preview [wanted] (remembering the user's theme to restore later), or restore the user's theme
     *  when an example carries none. Never persisted — a reload keeps the user's real theme. */
    private fun applyTheme(wanted: Theme?) {
        if (wanted != null) {
            if (savedTheme == null) savedTheme = ThemeManager.current
            if (ThemeManager.current != wanted) ThemeManager.use(wanted)
        } else {
            savedTheme?.let { ThemeManager.use(it); savedTheme = null }
        }
    }

    /**
     * Wipe everything the tour created: delete the child boards, empty the root, and navigate home
     * (the user may be looking at a child board when they leave). Back to a pristine empty board.
     */
    fun reset(model: Model, eventBus: EventBus<Event>) {
        applyTheme(null)                                             // restore the user's theme, if previewed
        val onChild = model.currentCanvas().name != rootCanvas
        wipeChildren(model)
        clearRootContent(model)
        model.addCanvas(canvasFromTemplate(rootCanvas, null, SINGLE_PANE))
        model.changeCanvas(rootCanvas)
        // If we were on a child board, fix the URL back to home. Either way force the root to
        // repaint as empty: RefreshCanvas bypasses the render idempotency guard, whereas a navigate
        // to an unchanged URL (the on-root case) would no-op and leave the example on screen.
        if (onChild) eventBus.fire(SwitchToCanvas(rootCanvas))
        eventBus.fire(RefreshCanvas(rootCanvas))
    }

    private fun wipeChildren(model: Model) {
        createdChildren.forEach { model.deleteCanvas(it) }
        createdChildren.clear()
    }

    private fun clearRootContent(model: Model) {
        model.getCanvas(rootCanvas)?.let { current ->
            current.columns
                .filterNot { current.layoutMap.containsKey(it) }
                .forEach { model.deleteColumn(rootCanvas, it) }
        }
    }
}
