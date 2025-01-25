package tdz.controller

import tdz.model.*
import zoned.framework.Debugger

object ColumnSplit {

    /**
     * Split this column in the direction indicated and return an updated canvas spec
     */
    fun splitColumn(canvas: Canvas, columnId: ColumnId, split: Split): Canvas {
        val containedBy = canvas.layouts.firstOrNull { it.columns.contains(columnId) }
            ?: throw Exception("Could not find container for $columnId ; $canvas")
        // special case. splitting a single column is really an add
        if (containedBy.columns.size == 1) {
            return addColumn(canvas, columnId, split)
        }
        val newSplitLayoutIdx = canvas.columns.last() + 1
        val newcolumnId = newSplitLayoutIdx + 1

        val updatedColumns = canvas.columns + listOf(newSplitLayoutIdx, newcolumnId)

        val updatedContainedBy = ColumnLayoutSpec(
            containedBy.columnId,
            containedBy.split,
            containedBy.columns.map {
                if (it == columnId) {
                    newSplitLayoutIdx
                } else {
                    it
                }
            })
        Debugger.debug(updatedContainedBy.toString())
        val newSplitLayout =
            ColumnLayoutSpec(newSplitLayoutIdx, split, listOf(columnId, newcolumnId))
        val updatedLayouts = canvas.layouts.map {
            if (it.columnId == containedBy.columnId) {
                updatedContainedBy
            } else {
                it
            }
        } + newSplitLayout

        return Canvas(canvas.name, canvas.parent, updatedColumns, updatedLayouts)
    }

    /**
     * Split this column in the direction indicated and return an updated canvas spec
     */
    fun addColumn(canvas: Canvas, columnId: ColumnId, split: Split): Canvas {
        val containedBy = canvas.layouts.firstOrNull { it.columns.contains(columnId) }!!
        val newcolumnId = canvas.columns.last() + 1

        if (split != containedBy.split && containedBy.columns.size > 1) {
            // split this layout
            return splitColumn(canvas, containedBy.columnId, split)
        } else {
            val updatedColumns = canvas.columns + newcolumnId
            val updatedContainedBy = ColumnLayoutSpec(
                containedBy.columnId,
                split, // this either switches (when there's one column) or is the same
                containedBy.columns + newcolumnId
            )

            val updatedLayouts = canvas.layouts.map {
                if (it.columnId == containedBy.columnId) {
                    updatedContainedBy
                } else {
                    it
                }
            }

            return Canvas(canvas.name, canvas.parent, updatedColumns, updatedLayouts)
        }
    }

    /**
     * Delete this column and rearrange to a sensible layout, returning an updated canvas model and a sensible
     * replacement column index
     */
    fun deleteColumn(canvas: Canvas, columnId: ColumnId): Pair<Canvas, ColumnId> {
        val containedBy = canvas.layouts.firstOrNull { it.columns.contains(columnId) }!!
        val updatedContains = containedBy.columns.filterNot { it == columnId }
        return if (canvas.columns.size == 2) {
            // special case - if trying to delete the last column, no!
            canvas to columnId
        } else if (updatedContains.size > 1 || (updatedContains.isNotEmpty() && canvas.columns.size == 3)) {
            // we have at least 2 remaining, or 1 remaining and we're going down to 1 non-layout column
            val Canvas = Canvas(canvas.name, canvas.parent, canvas.columns.filterNot { it == columnId },
                canvas.layouts.map {
                    if (it.columnId == containedBy.columnId) {
                        ColumnLayoutSpec(containedBy.columnId, containedBy.split, updatedContains)
                    } else {
                        it
                    }
                })
            val prevIdx = containedBy.columns.indexOf(columnId)
            val replacement = fanOutFrom(prevIdx, updatedContains, Canvas.layoutMap.keys.toSet()) ?: Canvas.columns.first { !Canvas.layoutMap.containsKey(it) }
            Canvas to replacement
        } else if (updatedContains.size == 1) {
            // drop the containing layout and move the child into where the containing layout was referred to
            var containsUpdatedLayout: ColumnId? = null
            val updatedLayouts = canvas.layouts.mapNotNull {
                if (it.columnId == containedBy.columnId) {
                    null
                } else if (it.columns.contains(containedBy.columnId)) {
                    containsUpdatedLayout = it.columnId
                    ColumnLayoutSpec(it.columnId, it.split, it.columns.map { contained ->
                        if (contained == containedBy.columnId) {
                            // copy the value from the prior layout, which we'll drop
                            updatedContains[0]
                        } else {
                            contained
                        }
                    })
                } else {
                    it
                }
            }
            val Canvas = Canvas(canvas.name, canvas.parent,
                canvas.columns.filterNot { it == columnId || it == containedBy.columnId },
                updatedLayouts)
            val replacement = if (containsUpdatedLayout != null) {
                val siblings = Canvas.layoutMap[containsUpdatedLayout]!!.columns
                fanOutFrom(siblings.indexOf(updatedContains[0]), siblings, Canvas.layoutMap.keys.toSet())
            } else {
                null
            }
            Canvas to (replacement ?: Canvas.columns.first { !Canvas.layoutMap.containsKey(it) })
        } else {
            throw Exception("Unexpected case: $canvas $columnId")
        }
    }

    // look backward and forward from index to find non-layout, null if none
    fun fanOutFrom(idx: Int, idList: List<ColumnId>, layouts: Set<ColumnId>): ColumnId? {
        for (i in 0 until idList.size - idx) {
            if ((idx - i) >= 0) {
                if (!layouts.contains(idList[idx - i])) {
                    return idList[idx - i]
                }
            }
            if ((idx + i) < idList.size) {
                if (!layouts.contains(idList[idx + i])) {
                    return idList[idx + i]
                }
            }
        }
        return null
    }

}