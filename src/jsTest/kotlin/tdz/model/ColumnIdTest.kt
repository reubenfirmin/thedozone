package tdz.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opaque column ids (P1): allocation is random, never `max+1`, so two devices adding a column
 * concurrently cannot mint the same id for different columns (sync keys by `col-<id>.md`).
 */
class ColumnIdTest {

    @Test fun rootIsStableZero() = assertEquals(ColumnId("0"), ColumnId.ROOT)

    @Test fun randomNeverReturnsAnExistingId() {
        val existing = ColumnId.randomMany(50).toSet()
        repeat(200) { assertFalse(ColumnId.random(existing) in existing) }
    }

    @Test fun randomManyAreDistinctAndAvoidExisting() {
        val existing = setOf(ColumnId.ROOT, ColumnId("1"))
        val ids = ColumnId.randomMany(30, existing)
        assertEquals(30, ids.toSet().size, "all allocated ids are distinct")
        assertTrue(ids.none { it in existing }, "none collide with the existing set")
    }

    @Test fun idsAreAlphanumericOnly() {
        // The id must avoid every separator reserved by the DOM/markdown/manifest/path formats
        // (`-` `:` `,` `|` `/` `.`) or those parsers would mis-split it.
        val re = Regex("^[A-Za-z0-9]+$")
        repeat(200) { assertTrue(re.matches(ColumnId.random().value)) }
    }

    @Test fun independentAllocationsDifferAcrossDevices() {
        // Two devices each allocating against an identical board state must not coincide (the old
        // `max+1` scheme produced the SAME id here — the cross-device collision).
        val board = setOf(ColumnId.ROOT, ColumnId("1"), ColumnId("2"))
        val deviceA = ColumnId.random(board)
        val deviceB = ColumnId.random(board)
        assertFalse(deviceA == deviceB, "concurrent adds on two devices get distinct ids")
    }
}
