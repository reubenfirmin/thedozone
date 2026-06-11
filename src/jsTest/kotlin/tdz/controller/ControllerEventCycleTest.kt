package tdz.controller

import tdz.model.ColumnId
import tdz.controller.events.CardsReordered
import tdz.controller.events.ClearDone
import tdz.controller.events.ColumnEdited
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.EventBus
import tdz.controller.events.RefreshColumns
import tdz.controller.events.TaskToggleDone
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.card.CardListItem
import tdz.model.card.ListGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The event cycle contract: views fire intent events; [Controller] is the only model mutator; every
 * LOCAL mutation emits [ColumnsChanged] (the model fact sync pushes on) and, where the screen must
 * update, [RefreshColumns] (the render command). A mutation that doesn't fire ColumnsChanged is a
 * change that silently never reaches other devices — the bug class this test pins down.
 */
class ControllerEventCycleTest {

    private class Captured(bus: EventBus) {
        val changed = mutableListOf<ColumnsChanged>()
        val refreshed = mutableListOf<RefreshColumns>()
        init {
            bus.on<ColumnsChanged> { changed += it }
            bus.on<RefreshColumns> { refreshed += it }
        }
    }

    private fun setup(name: String): Triple<EventBus, Model, Captured> {
        val bus = EventBus()
        val model = Model(CanvasName(name))
        Controller(bus, model)
        return Triple(bus, model, Captured(bus))
    }

    @Test
    fun taskToggleFiresColumnsChangedAndRefresh() {
        val (bus, model, captured) = setup("_cycle-toggle")
        val canvas = CanvasName("_cycle-toggle")
        model.updateColumn(canvas, ColumnId(1), "# Tasks\n* do it\n")
        val card = model.cards(canvas, ColumnId(1)).first()
        val item = (card.segments.first { it is ListGroup } as ListGroup).members
            .filterIsInstance<CardListItem>().first()

        bus.fire(TaskToggleDone(canvas, card, item))

        assertEquals(listOf(setOf(ColumnId(1))), captured.changed.map { it.columnIds }, "toggle is a model fact sync must see")
        assertTrue(captured.changed.none { it.remote }, "local mutations are not remote")
        assertEquals(listOf(setOf(ColumnId(1))), captured.refreshed.map { it.columnIds })
        val toggled = (model.cards(canvas, ColumnId(1)).first().segments.first { it is ListGroup } as ListGroup)
            .members.filterIsInstance<CardListItem>().first()
        assertTrue(toggled.done, "the model actually toggled")
    }

    @Test
    fun columnEditedFiresColumnsChangedButOnlyRefreshesWhenAsked() {
        val (bus, _, captured) = setup("_cycle-edit")
        val canvas = CanvasName("_cycle-edit")

        bus.fire(ColumnEdited("# From editor\n", canvas, ColumnId(2)))
        assertEquals(1, captured.changed.size, "editor saves must reach sync")
        assertEquals(0, captured.refreshed.size, "editor saves must NOT rebuild the editor mid-typing")

        bus.fire(ColumnEdited("# From properties modal\n", canvas, ColumnId(2), refresh = true))
        assertEquals(2, captured.changed.size)
        assertEquals(listOf(setOf(ColumnId(2))), captured.refreshed.map { it.columnIds }, "refresh=true renders once")
    }

    @Test
    fun cardsReorderedPersistsViaControllerAndFiresBothEvents() {
        val (bus, model, captured) = setup("_cycle-reorder")
        val canvas = CanvasName("_cycle-reorder")
        model.updateColumn(canvas, ColumnId(1), "# A\n\n# B\n")
        val (a, b) = model.cards(canvas, ColumnId(1))

        bus.fire(CardsReordered(canvas, toColumnId = ColumnId(1), toCards = listOf(b, a), fromColumnId = ColumnId(1), fromCards = null))

        val after = model.cards(canvas, ColumnId(1))
        assertEquals(listOf("B", "A"), after.map { (it.segments.first() as tdz.model.card.CardHeading).text.trim() })
        assertEquals(listOf(setOf(ColumnId(1))), captured.changed.map { it.columnIds })
        assertEquals(listOf(setOf(ColumnId(1))), captured.refreshed.map { it.columnIds })
    }

    @Test
    fun clearDoneFiresEventsOnlyWhenSomethingActuallyCleared() {
        val (bus, model, captured) = setup("_cycle-cleardone")
        val canvas = CanvasName("_cycle-cleardone")
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        model.updateColumn(canvas, ColumnId(1), "# T\n* /finished\n* open\n")

        bus.fire(ClearDone(canvas))

        assertEquals(listOf(setOf(ColumnId(1))), captured.changed.map { it.columnIds }, "clearing dones must reach sync")
        assertEquals(listOf(setOf(ColumnId(1))), captured.refreshed.map { it.columnIds })

        bus.fire(ClearDone(canvas))   // nothing left to clear
        assertEquals(1, captured.changed.size, "an all-clean canvas fires no events")
        assertEquals(1, captured.refreshed.size)
    }
}
