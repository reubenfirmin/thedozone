package tdz.view

import kotlinx.browser.window
import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.TWO_COLUMNS_VERTICAL
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import zoned.framework.interop.addToBody
import zoned.framework.interop.getHtmlElement
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

class CanvasColumnsFocusTest {

    /**
     * A probe point on the SHARED edge of two columns AND the canvas's bottom edge sits inside
     * both boundaries, but the inset test disqualifies both (one by x, one by y). The resolver
     * must fall back to a candidate instead of crashing — the crash was swallowed by
     * navigateFocus's try/catch, so the symptom was arrow keys silently refusing to move.
     */
    @Test
    fun sharedCornerProbeResolvesInsteadOfCrashing() {
        val bus = EventBus<Event>()
        val model = Model(CanvasName("_focus-test"))
        val canvas = canvasFromTemplate(CanvasName("_focus-test"), null, TWO_COLUMNS_VERTICAL)
        // Cross-column boundary resolution is a desktop concern (mobile renders one column with one
        // boundary); pin desktop so the two-boundary shared-edge case is actually exercised.
        val columns = CanvasColumns(canvas, bus, model, isMobile = { false }) { emptyList() }
        addToBody {
            div {
                id = "focus-host"
                with(columns) { render(canvas.columns) }
            }
        }
        try {
            val sharedX = (window.innerWidth.toDouble() / 2).roundToInt()   // columns' shared edge
            val bottomY = window.innerHeight                                 // canvas bottom edge
            val resolved = columns.findColumnSurrounding(sharedX, bottomY)
            assertTrue(resolved == canvas.columns[1] || resolved == canvas.columns[2],
                "a corner probe resolves to one of the adjacent leaf columns (got $resolved)")
        } finally {
            getHtmlElement("focus-host")?.remove()
        }
    }
}
