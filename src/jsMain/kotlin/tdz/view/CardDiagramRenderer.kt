package tdz.view

import kotlinx.css.*
import kotlinx.html.*
import tdz.model.Card
import tdz.model.ref
import tdz.model.card.CardDiagram
import tdz.view.theme.ThemeManager
import web.html.HTMLCanvasElement
import zoned.framework.dom.Ref
import zoned.framework.dom.onMount
import zoned.framework.dom.ref
import zoned.framework.interop.css
import zoned.framework.libs.loadNomnoml

/**
 * The nomnoml diagram integration extracted from [CardRenderer]. A [CardDiagram] segment renders to
 * a <canvas> whose colors are derived from the active theme; nomnoml itself is code-split and
 * fetched on the first card that actually has a diagram. Draw failures are logged and degrade to a
 * blank canvas rather than breaking the card.
 *
 * Named [CardDiagramRenderer] (not `CardDiagram`) to avoid colliding with the model type
 * `tdz.model.card.CardDiagram`.
 */
object CardDiagramRenderer {

    fun FlowContent.renderDiagram(segment: CardDiagram, card: Card) {
        val t = ThemeManager.current
        // Derive nomnoml's colors from the theme so lines/arrowheads/text stay legible in every theme.
        // Previously these were hardcoded (stroke white, fill black) — fine on dark themes, but the white
        // strokes/arrows were invisible against a light card. stroke = text color, fill = card background.
        val diagramStroke = t.fg
        val diagramFill = t.cardBg
        // Bias diagrams to flow left-to-right (cards are narrow and tall, so a wide diagram reads
        // better) — unless the author set their own #direction.
        val hasDirection = segment.text.lineSequence().any { it.trimStart().startsWith("#direction") }
        val noml = buildList {
            add("#stroke: $diagramStroke")
            add("#fill: $diagramFill")
            if (!hasDirection) add("#direction: right")
            add(segment.text)
        }.joinToString("\n")

        val canvasRef = Ref<HTMLCanvasElement>()
        canvas {
            id = DomIds.diagramCanvas(card.ref)
            ref(canvasRef)
            // nomnoml sets the canvas's *intrinsic* (bitmap) size to the diagram's natural pixels and
            // never touches CSS. Forcing width:100% (the old `w-full`) stretched a small diagram to the
            // full column width, distorting it huge. Instead show it at natural size, scaling DOWN to fit
            // the card width while preserving aspect ratio.
            css {
                display = Display.block
                maxWidth = 100.pct
                height = LinearDimension.auto
            }
            // Draw only after the element is in the DOM. The DSL builds the canvas in-memory and the
            // framework appends it in finalize() *then* runs onMount; a synchronous getElementById
            // during construction would (and did) return null, so draw never ran and the canvas was blank.
            onMount {
                // nomnoml is code-split: fetched on the first card that actually has a diagram.
                loadNomnoml().then { nomnoml ->
                    nomnoml.draw(canvasRef.element, noml)
                }.catch { e ->
                    console.error("nomnoml draw failed", e)
                }
            }
        }
    }
}
