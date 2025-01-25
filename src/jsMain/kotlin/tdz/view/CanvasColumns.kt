package tdz.view

import kotlinx.browser.window
import kotlinx.css.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.EventBus
import tdz.controller.events.EventType
import tdz.controller.events.NavColumn
import tdz.model.*
import tdz.model.Direction
import tdz.model.Direction.DOWN
import tdz.model.Direction.LEFT
import tdz.model.Direction.RIGHT
import tdz.model.Direction.UP
import tdz.model.Split.HORIZONTAL
import tdz.model.Split.VERTICAL
import tdz.view.Boundary.Companion.calculateBoundary
import web.keyboard.KeyCode
import zoned.framework.dom.ensureId
import zoned.framework.dom.onMount
import zoned.framework.interop.css
import zoned.framework.interop.el
import zoned.framework.interop.replaceClass

/** Encapsulated model/view of current columns */
class CanvasColumns(val canvas: Canvas,
                    val events: CanvasEventHandlers,
                    val eventBus: EventBus,
                    val cards: (columnId: Int) -> List<Card>) {

    private val boundaries = mutableMapOf<ColumnId, Boundary>()

    // TODO element attribute?
    private var focused = -1

    fun color(columnId: Int): Color {
        // TODO
        return Color.cyan
    }

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

                eventBus.on(el(this)!!, EventType.NAV_COLUMN) { e ->
                    focus((e as NavColumn).direction)
                }
            }
        }
    }

    // TODO check container dimensions rather than window's
    // note used in export, rendering, surrounds
    fun rootBoundary() = Boundary(0, 0, window.innerWidth, window.innerHeight)

    // TODO This should go into ColumnContainer??
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
                    val color = color(columnId)
                    with(ColumnContainer(color, canvas, Mode.CARD, colCards, boundary, columnId, events)) {
                        render()
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

    fun focus(direction: KeyCode) {
        // TODO we were tracking multiple events from ace editor - need a way to squash
        val newFocused = when (direction) {
            KeyCode.ArrowRight -> columnRight(focused)
            KeyCode.ArrowLeft -> columnLeft(focused)
            KeyCode.ArrowUp -> columnUp(focused)
            KeyCode.ArrowDown -> columnDown(focused)
        }
        focus(newFocused)
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

    private fun columnRight(currcolumnId: ColumnId): ColumnId {
        val boundary = boundaries[currcolumnId]!!
        return findColumnSurrounding(boundary.leftX + boundary.width + 1, boundary.topY)
    }

    private fun columnLeft(currcolumnId: ColumnId): ColumnId {
        val boundary = boundaries[currcolumnId]!!
        return findColumnSurrounding(boundary.leftX - 1, boundary.topY)
    }

    private fun columnUp(currcolumnId: ColumnId): ColumnId {
        val boundary = boundaries[currcolumnId]!!
        return findColumnSurrounding(boundary.leftX, boundary.topY - 1)
    }

    private fun columnDown(currcolumnId: ColumnId): ColumnId {
        val boundary = boundaries[currcolumnId]!!
        return findColumnSurrounding(boundary.leftX, boundary.topY + boundary.height + 1)
    }

}
