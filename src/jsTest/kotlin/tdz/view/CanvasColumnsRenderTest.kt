package tdz.view

import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.EventBus
import tdz.controller.events.RefreshColumns
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.TWO_COLUMNS_VERTICAL
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import zoned.framework.interop.addToBody
import zoned.framework.interop.getHtmlElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanvasColumnsRenderTest {

    @Test
    fun refreshColumnRebuildsInPlaceWithModeStyling() {
        val bus = EventBus()
        val model = Model(CanvasName("_rr-test"))
        val canvas = canvasFromTemplate(CanvasName("_rr-test"), null, TWO_COLUMNS_VERTICAL)
        val columns = CanvasColumns(canvas, bus, model) { emptyList() }
        addToBody {
            div {
                id = "rr-host"
                with(columns) { render(canvas.columns) }
            }
        }
        try {
            val grid = getHtmlElement("column-0")
            assertNotNull(grid, "layout grid is rendered")
            val orderBefore = (0 until grid.childElementCount).map { grid.children[it].id.toString() }
            assertEquals(listOf("column-1", "column-2"), orderBefore)

            bus.fire(RefreshColumns(canvas.name, setOf(1)))

            val orderAfter = (0 until grid.childElementCount).map { grid.children[it].id.toString() }
            assertEquals(listOf("column-1", "column-2"), orderAfter, "re-rendered column keeps its slot")

            val col1 = getHtmlElement("column-1")
            assertNotNull(col1)
            assertTrue(
                col1.className.toString().contains("overflow-y-auto"),
                "a CARD-mode column keeps its scroll class after a re-render",
            )
        } finally {
            columns.dispose()
            getHtmlElement("rr-host")?.remove()
        }
    }
}
