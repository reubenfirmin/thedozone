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
    val columnId: Int,
    val boundary: Boundary) {

    fun FlowContent.render() {
        div("w-full h-full mx-4 border-2") {
            id = containerId(columnId)
            columnInnards()
        }
    }

    private fun FlowContent.columnInnards() {

        val canvasName = canvas.name

        // on initial render this will be null; when we render editors, we should have the div initially
        val parent = el(containerId(columnId))
        // XXX boundary width and height is closer. not sure why parent is getting bad dims
        val (width, height) = parent?.getBoundingClientRect()?.let {
            it.width.toInt() to it.height.toInt()
        } ?: (boundary.width to boundary.height)
        when (mode) {
            CARD -> div {
                with(CardRenderer) {
                    render(cards)
                }
            }
            // TODO store cursorPosition somewhere?
            EDITOR -> with(EditorColumn(canvasName, width, height, columnId, null)) {
                render(cards)
            }
        }
    }

    fun toggleMode() {
        this.mode = when (mode) {
            CARD -> EDITOR
            EDITOR -> CARD
        }
        el(containerId(columnId))!!.apply {
            repaint {
                columnInnards()
            }
        }
    }

    companion object {
        fun containerId(column: ColumnId) = "column-${column}"
    }
 }