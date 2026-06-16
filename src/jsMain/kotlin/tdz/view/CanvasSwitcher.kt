package tdz.view

import kotlinx.browser.window
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.SwitchToCanvas
import tdz.model.CanvasName
import tdz.model.Model
import tdz.view.menu.ContextMenu
import tdz.view.menu.MenuItem
import tdz.view.menu.MenuSection

/**
 * Returns the direct children of [current] — boards whose parentName equals [current] — sorted
 * alphabetically by leaf. Uses the model as the authoritative source, so link-noise (e.g. the
 * auto-inserted `[..]` parent-link card) never pollutes the list.
 */
fun childBoardNames(model: Model, current: CanvasName): List<CanvasName> =
    model.allCanvasNames()
        .filter { !it.isRoot() && it.parentName().normalized() == current.normalized() }
        .sortedBy { it.leaf().lowercase() }

/** Pure builder for the child-board switcher menu (numbered for quick scanning). Empty => no menu. */
fun childBoardSections(children: List<CanvasName>, onSelect: (CanvasName) -> Unit): List<MenuSection> =
    if (children.isEmpty()) emptyList()
    else listOf(
        MenuSection(
            title = "Go to board",
            items = children.mapIndexed { i, child ->
                MenuItem(label = "${i + 1}. ${child.leaf()}", onSelect = { onSelect(child) })
            },
        ),
    )

/** Maps a number key ("1".."9") to the 0-based board index it selects, or null if out of range. */
fun switcherIndexForKey(key: String, count: Int): Int? =
    key.toIntOrNull()?.minus(1)?.takeIf { it in 0 until count }

/** Show the child-board switcher centered on screen (Page Down). Always offers "All boards…" — the
 *  global, searchable list — so boards with no children are still reachable. */
fun showCanvasSwitcher(model: Model, eventBus: EventBus<Event>) {
    val currentName = model.currentCanvas().name
    val children = childBoardNames(model, currentName)
    val childSections = childBoardSections(children) { child ->
        eventBus.fire(SwitchToCanvas(child))
    }
    // "All boards…" only when there's more than one board to go to.
    val allBoards = if (model.allCanvasNames().size > 1)
        listOf(MenuSection(items = listOf(
            MenuItem(label = "All boards…", onSelect = { AllBoards.show(model, eventBus) }),
        )))
    else emptyList()
    val sections = childSections + allBoards
    if (sections.isEmpty()) return   // nothing to switch to
    ContextMenu.show(
        window.innerWidth / 2 - 120,
        window.innerHeight / 2 - 120,
        sections,
    ) { event ->
        // 1..9 jump straight to that board (matching the numbered labels).
        switcherIndexForKey(event.key, children.size)?.let {
            eventBus.fire(SwitchToCanvas(children[it])); true
        } ?: false
    }
}
