package tdz.controller

import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Split
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Delete-column resilience on imperfect layout trees. Synced/merged manifests can carry shapes a
 * local split never produces — extra content columns outside the layout tree (a first-connect
 * manifest union), single-child layouts, stray layout ids. Deleting must stay safe on ALL of
 * them, and the "never delete the LAST content column" invariant must be structural (count the
 * content columns), not inferred from `columns.size == 2`.
 */
class ColumnSplitDeleteTest {

    private fun canvas(columns: List<Int>, vararg layouts: ColumnLayoutSpec) = Canvas(
        CanvasName("_csd"), null, columns.map { ColumnId(it.toString()) }, layouts.toList(),
    )

    private fun spec(id: Int, vararg contains: Int) =
        ColumnLayoutSpec(ColumnId(id.toString()), Split.VERTICAL, contains.map { ColumnId(it.toString()) })

    @Test
    fun splitAllocatesUnusedIdsEvenWhenColumnListIsNotSortedMaxLast() {
        // Whatever the order of `columns` (a sync manifest merge or wormhole import can leave it
        // unsorted), a split must allocate FRESH opaque ids that collide with no existing id —
        // reusing one would fold the layout tree into a cycle (the blank-board crash).
        val c = canvas(listOf(0, 5, 2, 1), spec(0, 5, 2, 1))
        val existing = c.columns.toSet()

        val split = ColumnSplit.splitColumn(c, ColumnId("2"), Split.VERTICAL)

        val added = split.columns.filterNot { it in existing }
        assertTrue(added.isNotEmpty(), "a split adds new ids")
        added.forEach { id ->
            assertTrue(id !in existing, "new id ${id.value} must not collide with an existing id")
        }
        // No layout may reference itself (the cycle the collision produced).
        split.layouts.forEach { l ->
            assertTrue(l.columnId !in l.contains, "layout ${l.columnId} must not contain itself")
        }
    }

    @Test
    fun lastContentColumnIsRefusedEvenWhenExtraLayoutIdsInflateTheCount() {
        // [0: layout([1]), 1: content, 9: empty layout] — the old size==2 guard saw size 3 and fell
        // through to "Unexpected delete case".
        val c = canvas(listOf(0, 1, 9), spec(0, 1), spec(9))
        val (updated, _) = ColumnSplit.deleteColumn(c, ColumnId("1"))
        assertEquals(c, updated, "the ONLY content column is never deleted")
    }

    @Test
    fun columnOutsideTheLayoutTreeDeletesCleanly() {
        // [0: layout([1]), 1: content, 2: content appended by a manifest union (no layout spec)]
        val c = canvas(listOf(0, 1, 2), spec(0, 1))
        val (updated, focus) = ColumnSplit.deleteColumn(c, ColumnId("2"))
        assertEquals(listOf(ColumnId("0"), ColumnId("1")), updated.columns, "orphan column removed")
        assertEquals(ColumnId("1"), focus, "focus lands on a remaining content column")
    }

    @Test
    fun singleChildLayoutCollapsesWithItsColumn() {
        // [0: layout([1, 3]), 3: layout([2])] — deleting 2 empties layout 3, which must collapse.
        val c = canvas(listOf(0, 1, 2, 3), spec(0, 1, 3), spec(3, 2))
        val (updated, _) = ColumnSplit.deleteColumn(c, ColumnId("2"))
        assertTrue(ColumnId("2") !in updated.columns, "column removed")
        assertTrue(ColumnId("3") !in updated.columns, "its emptied layout container removed too")
        assertEquals(listOf(ColumnId("1")), updated.layoutMap[ColumnId("0")]?.contains,
            "the parent layout no longer references the collapsed container")
    }
}
