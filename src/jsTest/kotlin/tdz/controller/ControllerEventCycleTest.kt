package tdz.controller

import tdz.model.ColumnId
import tdz.controller.events.CardsReordered
import tdz.controller.events.ClearDone
import tdz.controller.events.ColumnEdited
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshCard
import tdz.controller.events.RefreshColumns
import tdz.controller.events.TaskToggleDone
import tdz.model.ref
import tdz.model.CanvasName
import tdz.controller.events.DeleteColumn
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplate.TWO_COLUMNS_VERTICAL
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

    private class Captured(bus: EventBus<Event>) {
        val changed = mutableListOf<ColumnsChanged>()
        val refreshed = mutableListOf<RefreshColumns>()
        val cardRefreshed = mutableListOf<RefreshCard>()
        init {
            bus.on<ColumnsChanged> { changed += it }
            bus.on<RefreshColumns> { refreshed += it }
            bus.on<RefreshCard> { cardRefreshed += it }
        }
    }

    private fun setup(name: String): Triple<EventBus<Event>, Model, Captured> {
        val bus = EventBus<Event>()
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

        bus.fire(TaskToggleDone(canvas, card, ordinal = 0))

        assertEquals(listOf(setOf(ColumnId(1))), captured.changed.map { it.columnIds }, "toggle is a model fact sync must see")
        assertTrue(captured.changed.none { it.remote }, "local mutations are not remote")
        // A toggle repaints ONE glyph — it re-renders one card in place, never the whole column.
        assertEquals(listOf(card.ref), captured.cardRefreshed.map { it.ref })
        assertEquals(0, captured.refreshed.size, "no column rebuild for a single-card change")
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
    fun editorSaveDropsTrailingEmptyListItems() {
        val (bus, model, _) = setup("_cycle-trailing")
        val canvas = CanvasName("_cycle-trailing")

        // An empty trailing marker isn't a list item to the parser — it renders as a stray "*"
        // text line. Saving must drop it (and only it).
        bus.fire(ColumnEdited("# T\n* real item\n* \n", canvas, ColumnId(2)))

        assertEquals("# T\n* real item\n", model.getCanvasColumnRaw(canvas, ColumnId(2)),
            "the trailing empty bullet is dropped on save")

        // Empty markers ABOVE content are left alone — only the trailing one is the save artifact.
        bus.fire(ColumnEdited("# T\n* \n* real item\n", canvas, ColumnId(2)))
        assertEquals("# T\n* \n* real item\n", model.getCanvasColumnRaw(canvas, ColumnId(2)),
            "non-trailing markers are not the editor-save artifact and stay untouched")
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
    fun deletingTheLastColumnEmptiesItAndPropagatesToSync() {
        val (bus, model, captured) = setup("_cycle-lastcol")
        val canvas = CanvasName("_cycle-lastcol")
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        val col = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, col, "# T\n* stuff\n")

        bus.fire(tdz.controller.events.DeleteColumn(canvas, col))

        assertEquals(model.getCanvas(canvas)!!.columns, model.getCanvas(canvas)!!.columns,
            "canvas keeps its shape")
        assertEquals("", model.getCanvasColumnRaw(canvas, col),
            "the last column is EMPTIED (an empty file), never removed — removal left a null " +
            "that the next sync pull re-imported from the remote")
        assertEquals(listOf(setOf(col)), captured.changed.map { it.columnIds },
            "the emptying is a model fact: ColumnsChanged must fire so the remote converges")
    }

    @Test
    fun deletingEveryColumnNeverLeavesZeroContentColumns() {
        val (bus, model, _) = setup("_cycle-delall")
        val canvas = CanvasName("_cycle-delall")
        model.addCanvas(canvasFromTemplate(canvas, null, TWO_COLUMNS_VERTICAL))
        fun contentCols() = model.getCanvas(canvas)!!.let { c -> c.columns.filterNot { c.layoutMap.containsKey(it) } }
        assertTrue(contentCols().size >= 2, "setup: two content columns")

        // Hammer delete on every content column, several passes — the canvas must never hit zero.
        repeat(5) {
            contentCols().forEach { col -> bus.fire(DeleteColumn(canvas, col)) }
            assertTrue(contentCols().isNotEmpty(),
                "a canvas ALWAYS keeps at least one content column (the last delete empties, never removes)")
        }
        assertEquals(1, contentCols().size, "settles at exactly one content column")
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
