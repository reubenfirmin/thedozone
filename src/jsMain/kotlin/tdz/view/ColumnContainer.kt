package tdz.view

import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.EventBus
import tdz.model.Canvas
import tdz.model.Card
import tdz.view.Mode.*

/**
 * A column that contains cards.
 */
class ColumnContainer(
    headingColor: Color,
    val canvas: Canvas,
    val mode: Mode,
    val cards: List<Card>,
    val boundary: Boundary,
    private val columnId: Int,
    val eventBus: EventBus) {

    private val darkHeader = Border(2.px, BorderStyle.solid, color = Color.black)
    private val highlightHeader = Border(2.px, BorderStyle.solid, color = headingColor)

    private var focused = false

    fun FlowContent.render() {
        div("w-full h-full mx-4 border border-red-300 flex-1") {
            id = "column-${columnId}"
            paint()
        }
    }

    private fun FlowContent.paint() {
        val canvasName = canvas.name

        when (mode) {
            CARD -> div {
                with (CardRenderer) {
                    render(canvasName, cards, eventBus)
                }
            }
            EDITOR -> TODO("Unhandled")//EditorColumn(theme, canvasName, boundary.width,
                //cards, columnId, eventBus, dragDrop, cursorPosition, afterRender)
        }
    }

 }