package tdz.view

import tdz.model.ColumnId
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.CardMetadata
import tdz.model.card.CardDiagram
import kotlinx.html.div
import web.dom.document
import web.html.HTMLCanvasElement
import zoned.framework.interop.addToBody
import tdz.pollUntil
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Regression: nomnoml diagrams rendered blank. The renderer looked the canvas up via
 * document.getElementById() *synchronously during DSL construction* — before the framework
 * appended the element to the DOM (it appends in finalize(), then runs onMount callbacks).
 * The lookup returned null, the non-null cast threw, the silent catch swallowed it, and
 * draw() never ran. The fix defers draw() to onMount via a Ref, like the map/canvas-link renderers.
 * (nomnoml is code-split now, so the draw also waits on its chunk — hence the poll.)
 */
class CardDiagramRenderTest {

    @AfterTest
    fun cleanup() {
        document.body.asDynamic()?.innerHTML = ""
    }

    /** nomnoml paints shapes/text; until then every pixel is transparent. Scan the alpha channel. */
    private fun canvasHasInk(canvas: HTMLCanvasElement): Boolean {
        // Dynamic context avoids kotlin-wrappers getContext/ImageData overload churn.
        val ctx = canvas.asDynamic().getContext("2d")
        val data = ctx.getImageData(0, 0, canvas.width, canvas.height).data
        val length = data.length as Int
        var i = 3
        while (i < length) {
            if ((data[i] as Int) > 0) return true
            i += 4
        }
        return false
    }

    @Test
    fun diagramIsDrawnOntoCanvas(): Promise<Unit> {
        val card = Card(listOf(CardDiagram("[A] -> [B]")), CardMetadata(ColumnId(0)), 0)
        addToBody {
            div {
                with(CardRenderer) {
                    render(CanvasName("test"), listOf(card), EventBus<Event>())
                }
            }
        }

        return pollUntil("nomnoml chunk to load and paint the diagram") {
            (document.querySelector("canvas") as? HTMLCanvasElement)?.let { canvasHasInk(it) } == true
        }
    }
}
