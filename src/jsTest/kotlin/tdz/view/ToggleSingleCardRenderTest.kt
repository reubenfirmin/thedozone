package tdz.view

import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.Controller
import tdz.controller.events.Event
import tdz.controller.events.RefreshCard
import tdz.controller.events.TaskToggleDone
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.card.CardListItem
import tdz.model.card.ListGroup
import tdz.model.ref
import web.dom.document
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import zoned.framework.interop.getHtmlElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tier-1 performance contract for checkbox toggles: a [TaskToggleDone] used to rebuild the whole
 * column (every card, every inline style, every listener) to repaint one glyph. Now the Controller
 * fires [RefreshCard] and only that card element is rebuilt in place — its siblings must be the
 * SAME live DOM nodes afterwards.
 */
class ToggleSingleCardRenderTest {

    private val canvas = CanvasName("_tsc-test")
    private var column = ColumnId("1")   // reassigned to the board's opaque leaf after creation

    @AfterTest
    fun cleanup() {
        document.body.asDynamic()?.innerHTML = ""
    }

    @Test
    fun togglingATaskRebuildsOnlyThatCard() {
        val bus = EventBus<Event>()
        val model = Model(canvas)
        Controller(bus, model)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        column = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, column, "# Tasks\n* do it\n\n# Other\ncontent\n")

        val spec = model.getCanvas(canvas) ?: error("setup: canvas")
        val columns = CanvasColumns(spec, bus, model) { id -> model.cards(canvas, id) }
        // CanvasView owns these subscriptions in production; wire them the same way here.
        bus.on<RefreshCard> { e -> if (e.canvasName == canvas) columns.refreshCard(e.ref) }
        addToBody {
            div {
                id = "tsc-host"
                with(columns) { render(spec.columns.filter { !spec.layoutMap.containsKey(it) }) }
            }
        }

        val cards = model.cards(canvas, column)
        val taskCard = cards[0]
        val taskCardElBefore = getHtmlElement(DomIds.card(taskCard.ref))
        val siblingElBefore = getHtmlElement(DomIds.card(cards[1].ref))
        assertNotNull(taskCardElBefore); assertNotNull(siblingElBefore)
        val markerBefore = taskCardElBefore.querySelector(".tdz-task label > span")?.textContent

        bus.fire(TaskToggleDone(canvas, taskCard, ordinal = 0))

        val taskCardElAfter = getHtmlElement(DomIds.card(taskCard.ref))
        assertNotNull(taskCardElAfter, "the card keeps its id (toggle doesn't change order)")
        assertNotSame(taskCardElBefore, taskCardElAfter, "the toggled card was rebuilt")
        assertSame(siblingElBefore, getHtmlElement(DomIds.card(cards[1].ref)),
            "the sibling card is the SAME element — the column was not rebuilt")
        val markerAfter = taskCardElAfter.querySelector(".tdz-task label > span")?.textContent
        assertTrue(markerAfter != markerBefore && (markerAfter == "✓" || markerAfter == "[X]"),
            "the rebuilt card shows the done glyph (was '$markerBefore', now '$markerAfter')")
        assertTrue(model.cards(canvas, column)[0].let { c ->
            (c.segments.first { it is ListGroup } as ListGroup).members
                .filterIsInstance<CardListItem>().first().done
        }, "the model actually toggled")
    }

    @Test
    fun refreshCardWithStaleRefFallsBackToColumnRebuild() {
        val bus = EventBus<Event>()
        val model = Model(canvas)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        column = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, column, "# A\n")
        val spec = model.getCanvas(canvas) ?: error("setup: canvas")
        val columns = CanvasColumns(spec, bus, model) { id -> model.cards(canvas, id) }
        addToBody {
            div {
                id = "tsc-host"
                with(columns) { render(spec.columns.filter { !spec.layoutMap.containsKey(it) }) }
            }
        }

        // A ref pointing past the column's cards (stale id): must not throw, must still render.
        columns.refreshCard(tdz.model.CardRef(column, 99))
        assertEquals(1, document.querySelectorAll(".tdz-card").length,
            "fallback column rebuild leaves the column intact")
    }
}
