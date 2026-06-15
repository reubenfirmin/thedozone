package tdz.view

import tdz.controller.events.Event
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.Model
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.rootCanvas
import zoned.framework.events.EventBus
import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The intro wizard's "clear the canvas and replace it with this example" action: it rebuilds the
 * root board's layout + content in place, and leaves no cards behind from a previously-loaded
 * example.
 */
class ExampleLoaderTest {

    @AfterTest fun clean() { window.localStorage.clear() }

    private fun freshRootModel(): Model =
        Model(rootCanvas).also { it.addCanvas(canvasFromTemplate(rootCanvas, null, SINGLE_PANE)) }

    private fun contentColumnsOf(model: Model) =
        model.getCanvas(rootCanvas)!!.let { c -> c.columns.filterNot { c.layoutMap.containsKey(it) }.toSet() }

    @Test
    fun loadReplacesRootLayoutAndContent() {
        val model = freshRootModel()
        val week = ExampleBoards.ALL.first { it.title.contains("Week") }

        ExampleLoader.load(model, EventBus(), week)

        assertEquals(week.contentColumns().toSet(), contentColumnsOf(model),
            "root now carries the week planner's content columns")
        assertTrue(model.getCanvas(rootCanvas)!!.isLayoutSound())
        val firstDay = week.contentColumns().first()
        assertTrue(model.getCanvasColumnRaw(rootCanvas, firstDay)!!.contains("Monday"),
            "the first column's markdown was written")
    }

    @Test
    fun resetReturnsTheBoardToOneEmptyColumn() {
        val model = freshRootModel()
        ExampleLoader.load(model, EventBus(), ExampleBoards.ALL.first { it.title.contains("Week") })

        ExampleLoader.reset(model, EventBus())

        val canvas = model.getCanvas(rootCanvas)!!
        val content = canvas.columns.filterNot { canvas.layoutMap.containsKey(it) }
        assertEquals(1, content.size, "reset leaves a single content column")
        assertTrue(model.getCanvasColumnRaw(rootCanvas, content.first()).isNullOrBlank(), "and it's empty")
        assertTrue(canvas.isLayoutSound())
    }

    @Test
    fun loadSceneCreatesChildBoardsAndResetWipesThem() {
        val model = freshRootModel()
        ExampleLoader.loadScene(model, EventBus(), ExampleBoards.MULTI_BOARD)
        assertNotNull(model.getCanvas(CanvasName("Work")), "Work child board created")
        assertNotNull(model.getCanvas(CanvasName("Home")), "Home child board created")
        assertNotNull(model.getCanvas(CanvasName("Travel")), "Travel child board created")

        ExampleLoader.reset(model, EventBus())

        assertNull(model.getCanvas(CanvasName("Work")), "child boards wiped on reset")
        assertNull(model.getCanvas(CanvasName("Travel")), "child boards wiped on reset")
        val canvas = model.getCanvas(rootCanvas)!!
        assertEquals(1, canvas.columns.filterNot { canvas.layoutMap.containsKey(it) }.size,
            "root back to one empty column")
    }

    @Test
    fun loadClearsContentLeftByAPriorExample() {
        val model = freshRootModel()
        val week = ExampleBoards.ALL.first { it.title.contains("Week") }    // content cols 1..5
        val meeting = ExampleBoards.ALL.first { it.title.contains("Meeting") } // content cols 1..2

        ExampleLoader.load(model, EventBus(), week)
        ExampleLoader.load(model, EventBus(), meeting)

        assertEquals(meeting.contentColumns().toSet(), contentColumnsOf(model))
        assertNull(model.getCanvasColumnRaw(rootCanvas, ColumnId(3)),
            "a column the new example doesn't reuse must be cleared, not orphaned")
        assertTrue(model.getCanvasColumnRaw(rootCanvas, ColumnId(1))!!.contains("Product sync"),
            "the new example's content replaced the old")
    }
}
