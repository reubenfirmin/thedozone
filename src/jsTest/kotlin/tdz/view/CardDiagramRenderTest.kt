package tdz.view

import tdz.controller.events.EventBus
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.CardMetadata
import tdz.model.card.CardDiagram
import kotlinx.html.div
import web.dom.document
import web.html.HTMLCanvasElement
import zoned.framework.interop.addToBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression: nomnoml diagrams rendered blank. The renderer looked the canvas up via
 * document.getElementById() *synchronously during DSL construction* — before the framework
 * appended the element to the DOM (it appends in finalize(), then runs onMount callbacks).
 * The lookup returned null, the non-null cast threw, the silent catch swallowed it, and
 * draw() never ran. The fix defers draw() to onMount via a Ref, like the map/canvas-link renderers.
 */
class CardDiagramRenderTest {

    @AfterTest
    fun cleanup() {
        document.body?.asDynamic()?.innerHTML = ""
    }

    @Test
    fun diagramIsDrawnOntoCanvas() {
        val card = Card(listOf(CardDiagram("[A] -> [B]")), CardMetadata(0), 0)
        addToBody {
            div {
                with(CardRenderer) {
                    render(CanvasName("test"), listOf(card), EventBus())
                }
            }
        }

        val canvas = document.querySelector("canvas") as? HTMLCanvasElement
        assertNotNull(canvas, "a canvas element should be rendered for the diagram")

        // nomnoml paints shapes/text onto the canvas; with the bug nothing is drawn (all pixels
        // transparent). Scan the alpha channel for any painted pixel. Use a dynamic context to
        // avoid kotlin-wrappers getContext/ImageData overload churn.
        val ctx = canvas.asDynamic().getContext("2d")
        val data = ctx.getImageData(0, 0, canvas.width, canvas.height).data
        val length = data.length as Int
        var drew = false
        var i = 3
        while (i < length) {
            if ((data[i] as Int) > 0) {
                drew = true
                break
            }
            i += 4
        }
        assertTrue(drew, "nomnoml should have painted the diagram onto the canvas (it was blank)")
    }
}
