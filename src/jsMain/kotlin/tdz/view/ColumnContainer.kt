package tdz.view

import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.model.Canvas
import tdz.model.Card
import tdz.view.Mode.*
import zoned.framework.interop.css

/**
 * This persists between boards. It swaps rendering between CardColumn and EditorColumn, which it creates/destroys as
 * needed
 *
 */
class ColumnContainer(headingColor: Color,
                      val canvas: Canvas,
                      val mode: Mode,
                      val cards: List<Card>,
                      val boundary: Boundary,
                      private val columnId: Int) {

    private val darkHeader = Border(2.px, BorderStyle.solid, color = Color.black)
    private val highlightHeader = Border(2.px, BorderStyle.solid, color = headingColor)

    private var focused = false

    fun FlowContent.render() {
        div("w-full h-full mx-4 border border-red-300") {
            id = "column-${columnId}"
            paint()
        }
    }

    private fun FlowContent.paint() {
        val canvasName = canvas.name

        when (mode) {
            CARD -> div {
                with (CardRenderer) {
                    render(cards)
                }
            }
            EDITOR -> TODO("Unhandled")//EditorColumn(theme, canvasName, boundary.width,
                //cards, columnId, eventBus, dragDrop, cursorPosition, afterRender)
        }
    }

 }