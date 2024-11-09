package tdz.view

import kotlinx.browser.window
import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.model.*
import tdz.model.Direction.*
import tdz.model.Split.HORIZONTAL
import tdz.model.Split.VERTICAL
import tdz.view.Boundary.Companion.calculateBoundary
import zoned.framework.interop.css


/** Encapsulated model/view of current columns */
class CanvasColumns(val canvas: Canvas,
                    val cards: (columnId: Int) -> List<Card>) {

    private val boundaries = mutableMapOf<ColumnId, Boundary>()

    fun color(columnId: Int): Color {
        // TODO
        return Color.cyan
    }

    /**
     * @param container - container to render into
     * @param regenerateColumns - which columns should be recomputed (before re-rendering)
     * @param cardStyle - style to apply to cards
     */
    fun FlowContent.render(regenerateColumns: List<Int>) {
        val rootBoundary = rootBoundary()
        val rootColumnId = canvas.root().columnId
        boundaries[rootColumnId] = rootBoundary
        renderLayoutTree(rootColumnId, rootBoundary, regenerateColumns)
    }

    // TODO check container dimensions rather than window's
    // note used in export, rendering, surrounds
    fun rootBoundary() = Boundary(0, 0, window.innerWidth, window.innerHeight)

    // resolve a particular column layout, adding dom elements to it
    private fun FlowContent.renderLayoutTree(
        parentIndex: ColumnId, parentBoundary: Boundary, regenerateColumns: List<Int>) {

        val layoutSpec = canvas.layoutMap[parentIndex] ?: throw Exception("Layout $parentIndex not found in ${canvas.layoutMap}")
        div("border border-blue-300") {
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

            layoutSpec.contains.mapIndexed { idx, columnId ->
                val boundary = calculateBoundary(layoutSpec.split, parentBoundary, idx, layoutSpec.contains.size)
                if (canvas.layoutMap.containsKey(columnId)) {
                    // recursive
                    renderLayoutTree(columnId, boundary, regenerateColumns)
                } else {
                    if (columnId in regenerateColumns) {
                        val colCards = cards(columnId)
                        val color = color(columnId)
                        with(ColumnContainer(color, canvas, Mode.CARD, colCards, boundary, columnId)) {
                            render()
                        }
                    }
                }
                boundaries[columnId] = boundary
            }
        }

    }

    fun findColumnSurrounding(x: Int, y: Int, recurse: Boolean = false): Int {
        val candidates = boundaries.filter { entry ->
            val columnId = entry.key
            val boundary = entry.value
            !canvas.layoutMap.containsKey(columnId) && boundary.surrounds(x, y)
        }.toList()

        if (candidates.size > 1) {
            val best = candidates.filter {
                val inset = it.second.inset(x, y)
                inset.first >= x && inset.second >= y
            }
            if (best.size > 1 || best.isEmpty()) {
                val max = best.maxBy { it.second.leftX + it.second.topY }
                return max.first
            }
            return best.first().first
        } else if (candidates.isEmpty()) {
            rootBoundary().let {
                if (it.surrounds(x, y) || recurse) {
                    throw Exception("Couldn't find boundary containing $x $y - recursion $recurse")
                }

                val (newX, newY) = it.wrap(x, y)
                return findColumnSurrounding(newX, newY, true)
            }
        }
        return candidates.first().first
    }
}
