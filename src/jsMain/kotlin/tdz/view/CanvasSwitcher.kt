package tdz.view

import kotlinx.browser.window
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.SwitchToCanvas
import tdz.model.CanvasName
import tdz.model.Model
import tdz.model.card.CanvasLink
import tdz.view.menu.ContextMenu
import tdz.view.menu.MenuItem
import tdz.view.menu.MenuSection

/**
 * The distinct child-board links found in the cards of [canvasName], in first-seen order.
 * A "child board" is any canvas link embedded in one of the board's cards.
 */
fun childBoardLinks(model: Model, canvasName: CanvasName): List<CanvasLink> =
    model.allCards(canvasName)
        .flatten()
        .flatMap { it.segments }
        .flatMap { it.links() }
        .filterIsInstance<CanvasLink>()
        .distinctBy { it.canvasName.normalized() }

/** Pure builder for the child-board switcher menu (numbered for quick scanning). Empty => no menu. */
fun childBoardSections(links: List<CanvasLink>, onSelect: (CanvasName) -> Unit): List<MenuSection> =
    if (links.isEmpty()) emptyList()
    else listOf(
        MenuSection(
            title = "Go to board",
            items = links.mapIndexed { i, link ->
                MenuItem(label = "${i + 1}. ${link.label}", onSelect = { onSelect(link.canvasName) })
            },
        ),
    )

/** Maps a number key ("1".."9") to the 0-based board index it selects, or null if out of range. */
fun switcherIndexForKey(key: String, count: Int): Int? =
    key.toIntOrNull()?.minus(1)?.takeIf { it in 0 until count }

/** Show the child-board switcher centered on screen (Page Down). Always offers "All boards…" — the
 *  global, searchable list — so boards no card links to are still reachable. */
fun showCanvasSwitcher(model: Model, eventBus: EventBus<Event>) {
    val links = childBoardLinks(model, model.currentCanvas().name)
    val select = { target: CanvasName -> eventBus.fire(SwitchToCanvas(target)) }
    // "All boards…" only when there's more than one board to go to.
    val allBoards = if (model.allCanvasNames().size > 1)
        listOf(MenuSection(items = listOf(
            MenuItem(label = "All boards…", onSelect = { AllBoards.show(model, eventBus) }),
        )))
    else emptyList()
    val sections = childBoardSections(links, select) + allBoards
    if (sections.isEmpty()) return   // nothing to switch to
    ContextMenu.show(
        window.innerWidth / 2 - 120,
        window.innerHeight / 2 - 120,
        sections,
    ) { event ->
        // 1..9 jump straight to that board (matching the numbered labels).
        switcherIndexForKey(event.key, links.size)?.let { select(links[it].canvasName); true } ?: false
    }
}
