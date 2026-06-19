package tdz.model

import tdz.model.ColumnId
import tdz.model.markdown.MarkdownService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Clearing done items operates on list items nested inside [tdz.model.card.ListGroup]s
 * (card.segments holds the group, not the item) — the same structure toggleDone relies on.
 * A done item is written as a leading "/" after the bullet, e.g. `* /finished`.
 */
class ClearDoneTest {

    private fun clearedMd(md: String): String =
        MarkdownService.toMd(MarkdownService.fromMd(md, ColumnId("0")).map { it.withoutDoneItems() }, withMetadata = false)

    @Test
    fun removesCompletedItemsAndKeepsUnfinishedOnes() {
        val out = clearedMd("* keep me\n* /finished one\n* /finished two\n")
        assertTrue(out.contains("keep me"), "unfinished item retained; got: <<$out>>")
        assertFalse(out.contains("finished one"), "completed item removed; got: <<$out>>")
        assertFalse(out.contains("finished two"), "completed item removed; got: <<$out>>")
    }

    @Test
    fun removesCompletedItemsNestedInSubLists() {
        val out = clearedMd("* parent\n  * /done child\n  * keep child\n")
        assertTrue(out.contains("parent"), "parent retained; got: <<$out>>")
        assertTrue(out.contains("keep child"), "unfinished child retained; got: <<$out>>")
        assertFalse(out.contains("done child"), "completed nested item removed; got: <<$out>>")
    }

    @Test
    fun isStructurallyUnchangedWhenNothingIsDone() {
        // Model.clearDone relies on equality to decide whether a column changed.
        val card = MarkdownService.fromMd("* a\n* b\n", ColumnId("0")).first()
        assertEquals(card, card.withoutDoneItems(), "no done items must yield an equal card")
    }
}
