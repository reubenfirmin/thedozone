package tdz.model

import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplate.TWO_COLUMNS_VERTICAL
import tdz.model.LayoutTemplates.canvasFromTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A canvas must always be renderable. [Canvas.repaired] heals the states that left a board stuck
 * with no columns and no way to add one: a cyclic synced manifest, or a board deleted to nothing
 * before the delete-invariant existed. Sound canvases pass through untouched.
 */
class CanvasRepairTest {

    private fun canvas(columns: List<Int>, vararg layouts: ColumnLayoutSpec) =
        Canvas(CanvasName("_cr"), null, columns.map { ColumnId(it) }, layouts.toList())

    private fun spec(id: Int, vararg contains: Int) =
        ColumnLayoutSpec(ColumnId(id), Split.VERTICAL, contains.map { ColumnId(it) })

    private fun Canvas.content() = columns.filterNot { layoutMap.containsKey(it) }

    @Test fun soundCanvasIsUnchanged() {
        val c = canvasFromTemplate(CanvasName("_cr"), null, SINGLE_PANE)
        assertTrue(c.isLayoutSound())
        assertSame(c, c.repaired(), "a sound canvas must pass through untouched")
        val two = canvasFromTemplate(CanvasName("_cr"), null, TWO_COLUMNS_VERTICAL)
        assertSame(two, two.repaired())
    }

    @Test fun cyclicLayoutIsRepairedToSound() {
        val c = canvas(listOf(0, 1), spec(0, 1), spec(1, 0))   // 0→1→0
        assertTrue(!c.isLayoutSound(), "cycle is unsound")
        val r = c.repaired()
        assertTrue(r.isLayoutSound(), "repaired canvas is sound")
        assertTrue(r.content().isNotEmpty(), "has at least one content column")
    }

    @Test fun zeroContentColumnsGetsOneFreshColumn() {
        val c = canvas(listOf(0), spec(0))   // a lone empty layout, no content
        assertTrue(!c.isLayoutSound())
        val r = c.repaired()
        assertTrue(r.isLayoutSound())
        assertEquals(1, r.content().size, "exactly one fresh content column")
    }

    @Test fun repairPreservesExistingContentColumns() {
        // Two content columns (5, 6) but a cyclic layout among containers — their data must survive.
        val c = canvas(listOf(0, 1, 5, 6), spec(0, 1), spec(1, 0))
        val r = c.repaired()
        assertTrue(r.isLayoutSound())
        assertTrue(r.content().containsAll(listOf(ColumnId(5), ColumnId(6))),
            "existing content columns (and their cards) are preserved: ${r.content()}")
    }

    @Test fun repairedIdsDoNotCollideWithExisting() {
        val c = canvas(listOf(0, 1), spec(0, 1), spec(1, 0))
        val r = c.repaired()
        // The new root layout id must not be an existing column id (that's how cycles formed).
        val rootId = r.columns.first()
        assertTrue(r.layouts.single().columnId == rootId)
        assertTrue(rootId.value > listOf(0, 1).max(), "new root id is past the max existing id")
    }
}
