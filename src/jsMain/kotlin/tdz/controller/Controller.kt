package tdz.controller

import kotlinx.browser.document
import tdz.controller.events.EventBus
import tdz.controller.events.EventType.*
import tdz.controller.events.NavColumn
import tdz.controller.events.ToggleColumn
import tdz.model.*
import tdz.view.CanvasColumns
import tdz.view.ColumnContainer
import web.html.HTMLElement
import web.keyboard.KeyCode
import zoned.framework.interop.el
import zoned.framework.interop.repaint

class Controller(
    val model: Model,
    eventBus: EventBus) {

    private val downloader = Downloader(model)
    private val uploader = Uploader(model, eventBus)

    init {
        eventBus.on(TOGGLE_COLUMN) { e ->
            val column = (e as ToggleColumn).columnContainer
            column.toggleMode();
        }.on(NAV_COLUMN) { e ->
            val evt = e as NavColumn
            focus(evt.direction, evt.canvasColumns)
        }
    }

    // TODO all this stuff should go in sub-controller
    fun focus(direction: KeyCode, canvasColumns: CanvasColumns) {
        val newFocused = when (direction) {
            KeyCode.ArrowRight -> columnRight(canvasColumns)
            KeyCode.ArrowLeft -> columnLeft(canvasColumns)
            KeyCode.ArrowUp -> columnUp(canvasColumns)
            KeyCode.ArrowDown -> columnDown(canvasColumns)
        }
        canvasColumns.focus(newFocused)
    }

    private fun columnRight(canvasColumns: CanvasColumns): ColumnId {
        val boundary = canvasColumns.focusedBoundary()!!
        return findColumnSurrounding(canvasColumns, boundary.leftX + boundary.width + 1, boundary.topY)
    }

    private fun columnLeft(canvasColumns: CanvasColumns): ColumnId {
        val boundary = canvasColumns.focusedBoundary()!!
        return findColumnSurrounding(canvasColumns, boundary.leftX - 1, boundary.topY)
    }

    private fun columnUp(canvasColumns: CanvasColumns): ColumnId {
        val boundary = canvasColumns.focusedBoundary()!!
        return findColumnSurrounding(canvasColumns, boundary.leftX, boundary.topY - 1)
    }

    private fun columnDown(canvasColumns: CanvasColumns): ColumnId {
        val boundary = canvasColumns.focusedBoundary()!!
        return findColumnSurrounding(canvasColumns, boundary.leftX, boundary.topY + boundary.height + 1)
    }

    fun findColumnSurrounding(canvasColumns: CanvasColumns, x: Int, y: Int, recurse: Boolean = false): ColumnId {
        val candidates = canvasColumns.boundaries().filter { entry ->
            val columnId = entry.key
            val boundary = entry.value
            !canvasColumns.canvas.layoutMap.containsKey(columnId) && boundary.surrounds(x, y)
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
            canvasColumns.rootBoundary().let {
                if (it.surrounds(x, y) || recurse) {
                    throw Exception("Couldn't find boundary containing $x $y - recursion $recurse")
                }

                val (newX, newY) = it.wrap(x, y)
                return findColumnSurrounding(canvasColumns, newX, newY, true)
            }
        }
        return candidates.first().first
    }
}