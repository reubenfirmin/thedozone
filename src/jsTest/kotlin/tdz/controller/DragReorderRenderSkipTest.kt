package tdz.controller

import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.CardsReordered
import tdz.controller.events.Event
import tdz.controller.events.RefreshColumns
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.ColumnId
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.ref
import tdz.view.DomIds
import web.dom.Element
import web.dom.ElementId
import web.dom.document
import web.html.HTMLElement
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import zoned.framework.libs.SortableDropEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tier-1 performance contract for drag reorders: Sortable has ALREADY arranged the DOM when onEnd
 * fires, so rebuilding the column from the model just re-creates what the browser is showing. For
 * a single-card drag, DragDrop renumbers the positional card ids in place and marks the event
 * domAlreadyOrdered, and the Controller skips the RefreshColumns rebuild. Group (header) drags
 * keep the rebuild — the model reunites section children that Sortable never moved in the DOM.
 */
class DragReorderRenderSkipTest {

    private val canvas = CanvasName("_drs-test")
    private val column = ColumnId("1")

    @AfterTest
    fun cleanup() {
        document.body.asDynamic()?.innerHTML = ""
    }

    private fun buildModel(markdown: String): Model {
        val model = Model(canvas)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        model.updateColumn(canvas, column, markdown)
        return model
    }

    private fun buildDom(cards: List<Card>) {
        addToBody {
            div {
                id = DomIds.column(column)
                cards.forEach { card ->
                    div {
                        id = DomIds.card(card.ref)
                        if (card.isH1Only()) attributes["data-h1only"] = "true"
                    }
                }
            }
        }
    }

    private fun el(id: String): HTMLElement =
        document.getElementById(ElementId(id)) ?: error("missing $id")

    private fun domCardIds(container: Element): List<String> =
        (0 until container.children.length).mapNotNull { container.children[it].id.toString() }
            .filter { DomIds.isCard(it) }

    private fun dropEvent(item: Element, container: Element) = SortableDropEvent(
        itemId = item.id, fromId = container.id, toId = container.id,
        oldIndex = 0, newIndex = 0, item = item, from = container, to = container,
    )

    @Test
    fun singleCardDropRenumbersIdsAndSkipsTheRebuild() {
        val model = buildModel("# A\n\n# B\n\n# C\n")
        val cards = model.cards(canvas, column)
        buildDom(cards)
        val bus = EventBus<Event>()
        Controller(bus, model)
        val dragDrop = DragDrop(model, bus)

        var reordered: CardsReordered? = null
        var refreshes = 0
        bus.on<CardsReordered> { reordered = it }
        bus.on<RefreshColumns> { refreshes++ }

        // Simulate what Sortable has already done by onEnd: card C moved to the front.
        val columnEl = el(DomIds.column(column))
        val cardC = el(DomIds.card(cards[2].ref))
        columnEl.insertBefore(cardC, columnEl.firstChild)

        dragDrop.handleDrop(dropEvent(cardC, columnEl), canvas)

        reordered ?: error("CardsReordered should fire")
        assertEquals(0, refreshes, "single-card drag: no rebuild — the browser is already showing the result")
        assertEquals(listOf(" C", " A", " B"), model.cards(canvas, column).map { it.label() },
            "the model persisted the DOM order")
        assertEquals(listOf("card-1-0", "card-1-1", "card-1-2"), domCardIds(columnEl),
            "ids renumbered in DOM order, so the next drag decodes correct refs")
        assertEquals("card-1-0", cardC.id.toString(), "the moved element now encodes its new position")
    }

    @Test
    fun headerGroupDropStillRebuildsTheColumn() {
        // "# H" alone is an h1-only section header; A and B are its children.
        val model = buildModel("# H\n\n# A\nbody\n\n# B\nbody\n")
        val cards = model.cards(canvas, column)
        assertTrue(cards[0].isH1Only(), "setup: first card is a section header")
        buildDom(cards)
        val bus = EventBus<Event>()
        Controller(bus, model)
        val dragDrop = DragDrop(model, bus)

        var reordered: CardsReordered? = null
        var refreshes = 0
        bus.on<CardsReordered> { reordered = it }
        bus.on<RefreshColumns> { refreshes++ }

        // Sortable moved only the header element; the model will pull its children after it.
        val columnEl = el(DomIds.column(column))
        val header = el(DomIds.card(cards[0].ref))
        columnEl.appendChild(header)

        dragDrop.handleDrop(dropEvent(header, columnEl), canvas)

        reordered ?: error("CardsReordered should fire")
        assertEquals(1, refreshes, "group drag: the column must rebuild to reunite the section in the DOM")
    }

    private fun Card.label(): String =
        (segments.first() as tdz.model.card.CardHeading).text
}
