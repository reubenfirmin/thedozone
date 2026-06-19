package tdz.view

import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshColumns
import tdz.model.CanvasName
import tdz.model.ColumnId
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
        val bus = EventBus<Event>()
        val model = Model(CanvasName("_rr-test"))
        val canvas = canvasFromTemplate(CanvasName("_rr-test"), null, TWO_COLUMNS_VERTICAL)
        // Multi-column layout assertions are a desktop concern; pin desktop so the test is
        // independent of the (narrow) headless test viewport.
        val leaves = canvas.columns.filterNot { canvas.layoutMap.containsKey(it) }
        val expectedOrder = leaves.map { DomIds.column(it) }
        val columns = CanvasColumns(canvas, bus, model, isMobile = { false }) { emptyList() }
        addToBody {
            div {
                id = "rr-host"
                with(columns) { render(canvas.columns) }
            }
        }
        try {
            val grid = getHtmlElement(DomIds.column(ColumnId.ROOT))
            assertNotNull(grid, "layout grid is rendered")
            val orderBefore = (0 until grid.childElementCount).map { grid.children[it].id.toString() }
            assertEquals(expectedOrder, orderBefore)

            columns.refreshColumns(setOf(leaves[0]))   // (the RefreshColumns subscription is CanvasView's job)

            val orderAfter = (0 until grid.childElementCount).map { grid.children[it].id.toString() }
            assertEquals(expectedOrder, orderAfter, "re-rendered column keeps its slot")

            val col1 = getHtmlElement(DomIds.column(leaves[0]))
            assertNotNull(col1)
            // CARD columns scroll by wheel/touchpad but never SHOW a scrollbar — the to-date look
            // (no visible overflow chrome) with long columns still reachable.
            assertEquals("auto", col1.style.overflowY, "card columns are wheel/touch scrollable")
            assertEquals("none", col1.style.getPropertyValue("scrollbar-width"),
                "but the scrollbar itself is hidden")
        } finally {
            getHtmlElement("rr-host")?.remove()
        }
    }
}
