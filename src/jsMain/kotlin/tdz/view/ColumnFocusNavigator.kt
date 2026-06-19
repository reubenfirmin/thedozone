package tdz.view

import tdz.model.ColumnId
import tdz.model.Direction
import zoned.framework.Debugger

/**
 * Directional keyboard focus navigation across a canvas's leaf columns. Pulled out of
 * [CanvasColumns] (which owns the grid build, modes, styles, drag wiring) because focus geometry is
 * a self-contained cluster: given the live per-column [boundaries] it answers "which leaf column is
 * to the right/left/up/down of the current one".
 *
 * @param boundaries the LIVE per-column boundary map owned by [CanvasColumns] (read each call, so it
 *   always reflects the current render's geometry).
 * @param isLayout true for a layout (container) node id, false for a leaf (content) column.
 * @param rootBoundary the whole-viewport boundary, used to wrap a probe that lands outside every column.
 */
class ColumnFocusNavigator(
    private val boundaries: Map<ColumnId, Boundary>,
    private val isLayout: (ColumnId) -> Boolean,
    private val rootBoundary: () -> Boundary,
) {

    /**
     * The leaf column whose boundary surrounds ([x], [y]), or null when no column lies that way
     * (the probe wrapped off the viewport edge without landing on one). Returns null rather than
     * throwing for "no column there" — the directional probes treat null as "stay put".
     */
    fun findColumnSurrounding(x: Int, y: Int, recurse: Boolean = false): ColumnId? {
        val candidates = boundaries.filter { (columnId, boundary) ->
            !isLayout(columnId) && boundary.surrounds(x, y)
        }.toList()

        if (candidates.size > 1) {
            val best = candidates.filter {
                val inset = it.second.inset(x, y)
                inset.first >= x && inset.second >= y
            }
            if (best.size > 1 || best.isEmpty()) {
                // A probe on a shared edge can disqualify EVERY candidate (each loses on x or y);
                // fall back to the candidates rather than maxBy-ing an empty list.
                val max = best.ifEmpty { candidates }.maxBy { it.second.leftX + it.second.topY }
                return max.first
            }
            return best.first().first
        } else if (candidates.isEmpty()) {
            rootBoundary().let {
                // Off the viewport edge (or already wrapped once): no column that way.
                if (it.surrounds(x, y) || recurse) return null
                val (newX, newY) = it.wrap(x, y)
                return findColumnSurrounding(newX, newY, true)
            }
        }
        return candidates.first().first
    }

    /** The leaf column in [direction] from [current], or [current] itself when there is none that way. */
    fun navigate(direction: Direction, current: ColumnId): ColumnId = when (direction) {
        Direction.RIGHT -> columnRight(current)
        Direction.LEFT -> columnLeft(current)
        Direction.UP -> columnUp(current)
        Direction.DOWN -> columnDown(current)
    }

    // Directional probes aim at the MIDPOINT of the facing edge (not its corner): in a mixed
    // layout a corner probe can land beside a neighbour that only overlaps this column's middle.
    private fun columnRight(currColumnId: ColumnId): ColumnId {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return probe(currColumnId, boundary.leftX + boundary.width + 1, boundary.topY + boundary.height / 2)
    }

    private fun columnLeft(currColumnId: ColumnId): ColumnId {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return probe(currColumnId, boundary.leftX - 1, boundary.topY + boundary.height / 2)
    }

    private fun columnUp(currColumnId: ColumnId): ColumnId {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return probe(currColumnId, boundary.leftX + boundary.width / 2, boundary.topY - 1)
    }

    private fun columnDown(currColumnId: ColumnId): ColumnId {
        val boundary = boundaries[currColumnId] ?: return currColumnId
        return probe(currColumnId, boundary.leftX + boundary.width / 2, boundary.topY + boundary.height + 1)
    }

    /** Probe at ([x], [y]); stay on [currColumnId] when nothing lies that way (null result). */
    private fun probe(currColumnId: ColumnId, x: Int, y: Int): ColumnId =
        findColumnSurrounding(x, y) ?: currColumnId.also {
            Debugger.debug("focus probe found no column from $currColumnId at $x,$y")
        }
}
