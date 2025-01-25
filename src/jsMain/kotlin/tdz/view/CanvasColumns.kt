package tdz.view

import kotlinx.browser.window
import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.model.*
import tdz.model.Split.HORIZONTAL
import tdz.model.Split.VERTICAL
import tdz.view.Boundary.Companion.calculateBoundary
import zoned.framework.dom.onMount
import zoned.framework.interop.css
import zoned.framework.interop.el
import zoned.framework.interop.replaceClass

/** Encapsulated model/view of current columns */
class CanvasColumns(val canvas: Canvas,
                    val cards: (columnId: Int) -> List<Card>) {

    private val boundaries = mutableMapOf<ColumnId, Boundary>()

    // TODO element attribute?
    private var focused = -1

    fun focused() = focused

    /**
     * @param container - container to render into
     * @param regenerateColumns - which columns should be recomputed (before re-rendering)
     * @param cardStyle - style to apply to cards
     */
    fun FlowContent.render() {
        val rootBoundary = rootBoundary()
        val rootColumnId = canvas.root().columnId
        boundaries[rootColumnId] = rootBoundary
        div {
            renderLayoutTree(rootColumnId, rootBoundary)
            onMount {
                focus(focused)
            }
        }
    }

    // TODO check container dimensions rather than window's
    // note used in export, rendering, surrounds
    fun rootBoundary() = Boundary(0, 0, window.innerWidth, window.innerHeight)

    fun boundaries() = boundaries.toMap()

    // TODO This should go into ColumnLayout?
    // resolve a particular column layout, adding dom elements to it
    private fun FlowContent.renderLayoutTree(
        parentIndex: ColumnId, parentBoundary: Boundary) {

        val layoutSpec = canvas.layoutMap[parentIndex] ?: throw Exception("Layout $parentIndex not found in ${canvas.layoutMap}")
        div("border-2 border-blue-300") {
            id = "column-${layoutSpec.columnId}"
            css {
                display = Display.flex
                flexGrow = 1
                if (layoutSpec.split == HORIZONTAL) {
                    flexDirection = FlexDirection.column
                } else if (layoutSpec.split == VERTICAL) {
                    flexDirection = FlexDirection.row
                }
                justifyContent = JustifyContent.start
                width = 100.pct
                height = 100.pct
            }

            layoutSpec.columns.mapIndexed { idx, columnId ->
                val boundary = calculateBoundary(layoutSpec.split, parentBoundary, idx, layoutSpec.columns.size)
                if (canvas.layoutMap.containsKey(columnId)) {
                    // recursive
                    renderLayoutTree(columnId, boundary)
                } else {
                    if (focused == -1) {
                        focused = columnId
                    }
                    val colCards = cards(columnId)
                    with(ColumnContainer(canvas, Mode.CARD, colCards, columnId)) {
                        render()
                    }
                }
                boundaries[columnId] = boundary
            }
        }
    }

    fun focus(columnId: ColumnId) {
        val oldFocused = focused
        focused = columnId
        console.log("Focusing $columnId")
        el(ColumnContainer.containerId(focused))?.replaceClass("border-blue-700", "border-blue-100")

        if (oldFocused != focused) {
            blur(oldFocused)
        }
    }

    private fun blur(columnId: Int) {
        el(ColumnContainer.containerId(columnId))?.replaceClass("border-blue-100", "border-blue-700")
    }
}
