package tdz.view

import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.Controller
import tdz.controller.events.Event
import tdz.controller.events.TaskToggleDone
import tdz.model.Card.Companion.taskItems
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.card.Text
import web.dom.document
import web.html.HTMLElement
import web.mouse.MouseEvent
import web.mouse.MouseEventInit
import web.events.EventType
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Task rows carry NO per-row click closures: one delegated listener per column resolves the
 * clicked row back to its model segment positionally (the row's ordinal among the card's task
 * rows == the ordinal in Card.taskItems(), both being render order). With hundreds of tasks
 * that's hundreds of listeners and closures that no longer exist or rebuild on every render.
 */
class TaskClickDelegationTest {

    private val canvas = CanvasName("_tcd-test")
    private var column = ColumnId("1")   // reassigned to the board's opaque leaf after creation

    @AfterTest
    fun cleanup() {
        document.body.asDynamic()?.innerHTML = ""
    }

    private fun click(el: HTMLElement) {
        el.dispatchEvent(MouseEvent(EventType("click"), MouseEventInit(bubbles = true, cancelable = true)))
    }

    @Test
    fun clickingANestedTaskTogglesExactlyThatSegment() {
        val bus = EventBus<Event>()
        val model = Model(canvas)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        column = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, column, "# Tasks\n* parent task\n  * nested child\n\n# Other\n* other card task\n")
        val spec = model.getCanvas(canvas) ?: error("setup")
        val columns = CanvasColumns(spec, bus, model) { id -> model.cards(canvas, id) }
        addToBody {
            div {
                id = "tcd-host"
                with(columns) { render(spec.columns.filter { !spec.layoutMap.containsKey(it) }) }
            }
        }

        var toggled: TaskToggleDone? = null
        bus.on<TaskToggleDone> { toggled = it }

        val rows = document.querySelectorAll(".tdz-task")
        assertEquals(3, rows.length, "setup: three task rows render")
        // Click the marker SPAN inside the nested row — the event must bubble to the delegate.
        val nestedMarker = (rows[1] as HTMLElement).querySelector(".tdz-marker") as HTMLElement
        click(nestedMarker)

        val event = toggled
        assertNotNull(event, "the delegated listener fires the toggle")
        assertEquals("nested child",
            (event.card.taskItems()[event.ordinal].text.first() as Text).text.trim(),
            "the SECOND row resolves to the nested child, not its parent")
        assertEquals(canvas, event.canvasName)

        // A task in a different card resolves against ITS card's items.
        toggled = null
        click(rows[2] as HTMLElement)
        assertEquals("other card task",
            (toggled?.let { it.card.taskItems()[it.ordinal] }?.text?.first() as? Text)?.text?.trim(),
            "rows resolve per enclosing card")
    }

    /**
     * Identical rows are structurally EQUAL data classes, so any equality-based lookup in the
     * model flips them all: checking one of two "15 mins - ISO" rows checked the other too (and a
     * later Clear Done then removed items the user never touched). Identity must stay positional
     * end-to-end: click ordinal -> event -> model.
     */
    @Test
    fun clickingTheSecondOfTwoIdenticalTasksTogglesOnlyThatRow() {
        val bus = EventBus<Event>()
        val model = Model(canvas)
        Controller(bus, model)   // full round-trip: click -> TaskToggleDone -> model mutation
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        column = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, column, "# Routine\n* 15 mins - ISO\n* 15 mins - ISO\n")
        val spec = model.getCanvas(canvas) ?: error("setup")
        val columns = CanvasColumns(spec, bus, model) { id -> model.cards(canvas, id) }
        addToBody {
            div {
                id = "tcd-host2"
                with(columns) { render(spec.columns.filter { !spec.layoutMap.containsKey(it) }) }
            }
        }

        val rows = document.querySelectorAll(".tdz-task")
        assertEquals(2, rows.length, "setup: both twins render")
        click(rows[1] as HTMLElement)

        assertEquals(listOf(false, true), model.cards(canvas, column).first().taskItems().map { it.done },
            "identical twins are distinct rows — only the CLICKED one toggles")
    }

    @Test
    fun clicksOutsideTaskRowsAreIgnored() {
        val bus = EventBus<Event>()
        val model = Model(canvas)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        column = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, column, "# Tasks\n* a task\nplain body text\n")
        val spec = model.getCanvas(canvas) ?: error("setup")
        val columns = CanvasColumns(spec, bus, model) { id -> model.cards(canvas, id) }
        addToBody {
            div {
                id = "tcd-host"
                with(columns) { render(spec.columns.filter { !spec.layoutMap.containsKey(it) }) }
            }
        }

        var toggled: TaskToggleDone? = null
        bus.on<TaskToggleDone> { toggled = it }
        click(document.querySelector(".tdz-card") as HTMLElement)
        assertNull(toggled, "a click on card chrome (not a task row) must not toggle anything")
    }
}
