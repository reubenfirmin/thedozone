package tdz.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Model.cards() used to re-read localStorage AND re-parse the column markdown on EVERY call —
 * getCardByIndex calls it per lookup, so a drop in an n-card column parsed the markdown n times
 * (O(n²)) right on the interaction path. The cache is keyed by raw-markdown equality, NOT by
 * write-path invalidation hooks: any write from any path (editor, drag, sync pull writing
 * localStorage underneath) changes the raw string and naturally misses the cache.
 */
class ModelParseCacheTest {

    private val canvas = CanvasName("_pc-test")
    private val column = ColumnId(1)

    @Test
    fun repeatedReadsReturnTheCachedParse() {
        val model = Model(canvas)
        model.updateColumn(canvas, column, "# A\n* task one\n\n# B\n")
        assertSame(model.cards(canvas, column), model.cards(canvas, column),
            "unchanged column → the same parsed list instance (no re-parse)")
    }

    @Test
    fun writesAreSeenImmediately() {
        val model = Model(canvas)
        model.updateColumn(canvas, column, "# A\n")
        val before = model.cards(canvas, column)
        model.updateColumn(canvas, column, "# A\n\n# B\n")
        val after = model.cards(canvas, column)
        assertNotSame(before, after)
        assertEquals(2, after.size, "the fresh parse reflects the written content")
    }

    @Test
    fun columnsAndCanvasesDoNotCrossContaminate() {
        val model = Model(canvas)
        val other = CanvasName("_pc-other")
        model.updateColumn(canvas, column, "# A\n")
        model.updateColumn(canvas, ColumnId(2), "# Z\n")
        model.updateColumn(other, column, "# Q\n")
        fun heading(c: CanvasName, col: ColumnId) =
            (model.cards(c, col).first().segments.first() as tdz.model.card.CardHeading).text.trim()
        assertEquals("A", heading(canvas, column))
        assertEquals("Z", heading(canvas, ColumnId(2)))
        assertEquals("Q", heading(other, column))
    }

    @Test
    fun writesThatBypassThisModelInstanceAreStillSeen() {
        // Sync pulls (or a second tab) write localStorage without going through this Model
        // instance. Raw-equality caching must pick that up — no stale reads.
        val model = Model(canvas)
        model.updateColumn(canvas, column, "# A\n")
        assertTrue(model.cards(canvas, column).isNotEmpty())

        val backdoor = Model(canvas)   // same localStorage underneath
        backdoor.updateColumn(canvas, column, "# A\n\n# External\n")

        assertEquals(2, model.cards(canvas, column).size,
            "a write that bypassed this instance's write path is still visible")
    }
}
