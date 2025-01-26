package tdz.view

import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.model.Canvas
import tdz.model.Card
import tdz.model.ColumnId
import tdz.view.Mode.*
import zoned.framework.interop.el
import zoned.framework.interop.repaint

class ColumnContainer(
    val canvas: Canvas,
    var mode: Mode,
    val cards: List<Card>,
    private val columnId: Int,
    private val boundary: Boundary) {

    fun FlowContent.render() {
        div("w-full h-full mx-4 border-2 flex") {
            id = containerId(columnId)
            columnInnards()
        }
    }

    private fun FlowContent.columnInnards() {

        val canvasName = canvas.name

        when (mode) {
            CARD -> div {
                with(CardRenderer) {
                    render(cards)
                }
            }
            // TODO store cursorPosition somewhere?
            EDITOR -> with(EditorColumn(canvasName, boundary.width, columnId, null)) {
                render(cards)
            }
        }
    }

    fun toggleMode() {
        this.mode = when (mode) {
            CARD -> EDITOR
            EDITOR -> CARD
        }
        el(containerId(columnId))!!.repaint {
            columnInnards()
        }
    }

    companion object {
        fun containerId(column: ColumnId) = "column-${column}"
    }
 }