package tdz.controller

import tdz.model.Canvas
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Split

/**
 * Pure transforms over a [Canvas]'s layout tree. Ported from the original thedozone.
 *
 * - [splitColumn] ("even"): divide a column into two equal panes in the given direction.
 * - [addColumn] ("distribute"): add a pane to the containing layout in the given direction.
 *
 * Column ids are OPAQUE and random ([ColumnId.random]), not `max+1`: two devices adding a column
 * concurrently must never mint the same id for different columns (sync keys by `col-<id>.md`, so a
 * shared id would merge two unrelated columns). Random ids also can't collide with an existing id
 * and fold the layout tree into a cycle — allocation excludes the current column set.
 */
object ColumnSplit {

    /** A fresh opaque id not already used in [canvas] (plus any [extra] just allocated in the same op). */
    private fun nextId(canvas: Canvas, extra: Set<ColumnId> = emptySet()): ColumnId =
        ColumnId.random(canvas.columns.toSet() + extra)

    /** Split [columnId] into two equal panes in direction [split]. */
    fun splitColumn(canvas: Canvas, columnId: ColumnId, split: Split): Canvas {
        val containedBy = canvas.layouts.firstOrNull { it.contains.contains(columnId) }
            ?: throw Exception("Could not find container for $columnId ; $canvas")
        // Splitting the only column in a layout is really just an add.
        if (containedBy.contains.size == 1) {
            return addColumn(canvas, columnId, split)
        }
        val newSplitLayoutIdx = nextId(canvas)
        val newColumnId = nextId(canvas, setOf(newSplitLayoutIdx))

        val updatedColumns = canvas.columns + listOf(newSplitLayoutIdx, newColumnId)

        val updatedContainedBy = ColumnLayoutSpec(
            containedBy.columnId,
            containedBy.split,
            containedBy.contains.map { if (it == columnId) newSplitLayoutIdx else it },
        )
        val newSplitLayout = ColumnLayoutSpec(newSplitLayoutIdx, split, listOf(columnId, newColumnId))
        val updatedLayouts = canvas.layouts.map {
            if (it.columnId == containedBy.columnId) updatedContainedBy else it
        } + newSplitLayout

        return Canvas(canvas.name, canvas.parent, updatedColumns, updatedLayouts)
    }

    /** Add a pane next to [columnId] in the containing layout, in direction [split]. */
    fun addColumn(canvas: Canvas, columnId: ColumnId, split: Split): Canvas {
        val containedBy = canvas.layouts.firstOrNull { it.contains.contains(columnId) }
            ?: throw Exception("Could not find container for $columnId ; $canvas")
        val newColumnId = nextId(canvas)

        return if (split != containedBy.split && containedBy.contains.size > 1) {
            // Different direction than the containing layout => split the column instead.
            splitColumn(canvas, containedBy.columnId, split)
        } else {
            val updatedColumns = canvas.columns + newColumnId
            val updatedContainedBy = ColumnLayoutSpec(
                containedBy.columnId,
                split, // switches when there was a single column, otherwise unchanged
                containedBy.contains + newColumnId,
            )
            val updatedLayouts = canvas.layouts.map {
                if (it.columnId == containedBy.columnId) updatedContainedBy else it
            }
            Canvas(canvas.name, canvas.parent, updatedColumns, updatedLayouts)
        }
    }

    /**
     * Delete [columnId], rearranging to a sensible layout, and return the updated canvas plus a
     * sensible replacement column to focus. When only one sibling remains, the containing layout
     * is collapsed and that sibling is pulled up into the grandparent. Refuses to delete the last
     * column (returns the canvas unchanged). The deleted column's cards are handled by the caller.
     */
    fun deleteColumn(canvas: Canvas, columnId: ColumnId): Pair<Canvas, ColumnId> {
        // The "always one column" invariant is STRUCTURAL: count content columns (not layout
        // containers). Synced/merged manifests can carry extra ids that broke a size-based check.
        val contentColumns = canvas.columns.filterNot { canvas.layoutMap.containsKey(it) }
        if (columnId in contentColumns && contentColumns.size <= 1) return canvas to columnId
        val containedBy = canvas.layouts.firstOrNull { it.contains.contains(columnId) }
            ?: run {
                // A column outside the layout tree (e.g. appended by a first-connect manifest
                // union): no layout to rewrite — plain removal from the columns list.
                val updated = Canvas(canvas.name, canvas.parent,
                    canvas.columns.filterNot { it == columnId }, canvas.layouts)
                return updated to (contentColumns.firstOrNull { it != columnId } ?: columnId)
            }
        val updatedContains = containedBy.contains.filterNot { it == columnId }
        return when {
            // Special case: never delete the last column.
            canvas.columns.size == 2 -> canvas to columnId

            // At least 2 remain, or 1 remains and we're down to a single non-layout column.
            updatedContains.size > 1 || (updatedContains.isNotEmpty() && canvas.columns.size == 3) -> {
                val updated = Canvas(
                    canvas.name, canvas.parent,
                    canvas.columns.filterNot { it == columnId },
                    canvas.layouts.map {
                        if (it.columnId == containedBy.columnId) {
                            ColumnLayoutSpec(containedBy.columnId, containedBy.split, updatedContains)
                        } else it
                    },
                )
                val prevIdx = containedBy.contains.indexOf(columnId)
                val replacement = fanOutFrom(prevIdx, updatedContains, updated.layoutMap.keys.toSet())
                    ?: updated.columns.first { !updated.layoutMap.containsKey(it) }
                updated to replacement
            }

            // One remains: drop the containing layout, move its child up into the grandparent.
            updatedContains.size == 1 -> {
                var grandparent: ColumnId? = null
                val updatedLayouts = canvas.layouts.mapNotNull {
                    when {
                        it.columnId == containedBy.columnId -> null
                        it.contains.contains(containedBy.columnId) -> {
                            grandparent = it.columnId
                            ColumnLayoutSpec(it.columnId, it.split, it.contains.map { contained ->
                                if (contained == containedBy.columnId) updatedContains[0] else contained
                            })
                        }
                        else -> it
                    }
                }
                val updated = Canvas(
                    canvas.name, canvas.parent,
                    canvas.columns.filterNot { it == columnId || it == containedBy.columnId },
                    updatedLayouts,
                )
                val replacement = grandparent?.let { gp ->
                    // The grandparent layout was rewritten (not removed) in the mapNotNull above,
                    // so it must be present; a miss means the layout tree is corrupt.
                    val siblings = updated.layoutMap[gp]?.contains
                        ?: error("Grandparent layout $gp missing after collapsing $columnId — layout tree is corrupt")
                    fanOutFrom(siblings.indexOf(updatedContains[0]), siblings, updated.layoutMap.keys.toSet())
                } ?: updated.columns.first { !updated.layoutMap.containsKey(it) }
                updated to replacement
            }

            // The layout contained ONLY the deleted column (a shape merges can produce): the
            // emptied container collapses too, and its parent stops referencing it.
            updatedContains.isEmpty() -> {
                val updatedLayouts = canvas.layouts.mapNotNull {
                    when {
                        it.columnId == containedBy.columnId -> null
                        it.contains.contains(containedBy.columnId) ->
                            ColumnLayoutSpec(it.columnId, it.split,
                                it.contains.filterNot { c -> c == containedBy.columnId })
                        else -> it
                    }
                }
                val updated = Canvas(canvas.name, canvas.parent,
                    canvas.columns.filterNot { it == columnId || it == containedBy.columnId },
                    updatedLayouts)
                updated to (updated.columns.firstOrNull { !updated.layoutMap.containsKey(it) } ?: columnId)
            }

            else -> throw Exception("Unexpected delete case: $canvas $columnId")
        }
    }

    /** Look backward and forward from [idx] in [idList] for a non-layout column id; null if none. */
    private fun fanOutFrom(idx: Int, idList: List<ColumnId>, layouts: Set<ColumnId>): ColumnId? {
        for (i in 0 until idList.size - idx) {
            if (idx - i >= 0 && !layouts.contains(idList[idx - i])) return idList[idx - i]
            if (idx + i < idList.size && !layouts.contains(idList[idx + i])) return idList[idx + i]
        }
        return null
    }
}
