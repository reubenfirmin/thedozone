package tdz.view

import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.Event
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Model
import tdz.model.Split
import web.dom.document
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import zoned.framework.interop.getHtmlElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A corrupt manifest whose LAYOUT tree contains a cycle (a layout column referencing an ancestor)
 * must not take down the app. renderLayoutTree recurses through `contains`; without a cycle guard
 * it recurses until the stack overflows, aborting the whole board render — the real cause of the
 * blank board (the CardRenderer frames in the trace were just the leaf where the stack tipped over).
 */
class CyclicLayoutRenderTest {

    @AfterTest fun cleanup() { document.body.asDynamic()?.innerHTML = "" }

    @Test
    fun cyclicLayoutRendersWithoutOverflowing() {
        val name = CanvasName("_cyc")
        val model = Model(name)
        // Layout 0 contains 1; layout 1 contains 0 → 0→1→0→… an infinite render loop.
        val canvas = Canvas(
            name, null,
            columns = listOf(ColumnId(0), ColumnId(1)),
            layouts = listOf(
                ColumnLayoutSpec(ColumnId(0), Split.VERTICAL, listOf(ColumnId(1))),
                ColumnLayoutSpec(ColumnId(1), Split.VERTICAL, listOf(ColumnId(0))),
            ),
        )
        model.addCanvas(canvas)
        val bus = EventBus<Event>()
        // Desktop layout-tree render: the cycle guard lives in renderLayoutTree (mobile shows a
        // single column and never walks the tree). Pin desktop so the test is viewport-independent.
        val columns = CanvasColumns(canvas, bus, model, isMobile = { false }) { emptyList() }

        // Must not throw "Maximum call stack size exceeded".
        addToBody {
            div {
                id = "cyc-host"
                with(columns) { render(canvas.columns) }
            }
        }

        assertTrue(getHtmlElement("cyc-host") != null, "the board renders (cycle broken), app survives")
    }
}
