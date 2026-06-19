package tdz.view

import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.Event
import zoned.framework.events.EventBus
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
import kotlin.test.assertNull

class CanvasColumnsMobileTest {

    private fun twoColumnCanvas() =
        canvasFromTemplate(CanvasName("_mobile-test"), null, TWO_COLUMNS_VERTICAL)

    @Test
    fun firstLeafIsTopLeftContentColumn() {
        val bus = EventBus<Event>()
        val canvas = twoColumnCanvas()
        val columns = CanvasColumns(canvas, bus, Model(CanvasName("_mobile-test"))) { emptyList() }
        // The root layout (ColumnId.ROOT) is skipped; its first content child is the first leaf.
        assertEquals(canvas.firstNonLayout(), columns.firstLeafColumn())
    }

    @Test
    fun mobileRendersOnlyFirstColumn() {
        val bus = EventBus<Event>()
        val model = Model(CanvasName("_mobile-test"))
        val canvas = twoColumnCanvas()
        val leaves = canvas.columns.filterNot { canvas.layoutMap.containsKey(it) }
        // Force mobile via the injected breakpoint seam — no dependency on the real viewport width.
        val columns = CanvasColumns(canvas, bus, model, isMobile = { true }) { emptyList() }
        addToBody {
            div {
                id = "mobile-host"
                with(columns) { render(canvas.columns) }
            }
        }
        try {
            assertNotNull(getHtmlElement(DomIds.column(leaves[0])), "the first content column is rendered")
            assertNull(getHtmlElement(DomIds.column(ColumnId.ROOT)), "the layout grid is skipped on mobile")
            assertNull(getHtmlElement(DomIds.column(leaves[1])), "other columns are not in the DOM on mobile")
        } finally {
            getHtmlElement("mobile-host")?.remove()
        }
    }

    @Test
    fun desktopRendersFullLayout() {
        val bus = EventBus<Event>()
        val model = Model(CanvasName("_mobile-test"))
        val canvas = twoColumnCanvas()
        val leaves = canvas.columns.filterNot { canvas.layoutMap.containsKey(it) }
        val columns = CanvasColumns(canvas, bus, model, isMobile = { false }) { emptyList() }
        addToBody {
            div {
                id = "desktop-host"
                with(columns) { render(canvas.columns) }
            }
        }
        try {
            assertNotNull(getHtmlElement(DomIds.column(ColumnId.ROOT)), "layout grid present on desktop")
            assertNotNull(getHtmlElement(DomIds.column(leaves[0])))
            assertNotNull(getHtmlElement(DomIds.column(leaves[1])), "all columns present on desktop")
        } finally {
            getHtmlElement("desktop-host")?.remove()
        }
    }
}
