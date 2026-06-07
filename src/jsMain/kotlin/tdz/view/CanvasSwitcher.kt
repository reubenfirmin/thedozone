package tdz.view

import kotlinx.browser.window
import tdz.controller.events.EventBus
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

/** Show the child-board switcher centered on screen (Page Down). No-op when there are no sub-boards. */
fun showCanvasSwitcher(model: Model, eventBus: EventBus) {
    val links = childBoardLinks(model, model.currentCanvas().name)
    if (links.isEmpty()) return
    val select = { target: CanvasName -> eventBus.fire(SwitchToCanvas(target)) }
    ContextMenu.show(
        window.innerWidth / 2 - 120,
        window.innerHeight / 2 - 120,
        childBoardSections(links, select),
    ) { event ->
        // 1..9 jump straight to that board (matching the numbered labels).
        switcherIndexForKey(event.key, links.size)?.let { select(links[it].canvasName); true } ?: false
    }
}
