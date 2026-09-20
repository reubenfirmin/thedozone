package tdz.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColumnMergeTest {

    @Test
    fun nonOverlappingEditsMergeCleanly() {
        val base   = "# Card A\nbody a\n\n# Card B\nbody b\n"
        val local  = "# Card A\nbody a EDITED\n\n# Card B\nbody b\n"
        val remote = "# Card A\nbody a\n\n# Card B\nbody b EDITED\n"

        val result = ColumnMerge.mergeColumn(local = local, base = base, remote = remote)

        assertFalse(result.hadConflict, "different cards -> clean merge")
        assertTrue(result.merged.contains("body a EDITED"), "local edit kept")
        assertTrue(result.merged.contains("body b EDITED"), "remote edit kept")
    }

    @Test
    fun overlappingEditsReportConflict() {
        val base   = "# Card A\nshared line\n"
        val local  = "# Card A\nshared line LOCAL\n"
        val remote = "# Card A\nshared line REMOTE\n"

        val result = ColumnMerge.mergeColumn(local = local, base = base, remote = remote)

        assertTrue(result.hadConflict, "same-line edits -> conflict")
    }

    @Test
    fun identicalSidesNoConflict() {
        val s = "# Card A\nbody\n"
        val result = ColumnMerge.mergeColumn(local = s, base = s, remote = s)
        assertFalse(result.hadConflict)
        assertEquals(s, result.merged)
    }

    @Test
    fun nullBaseFallsBackToTwoWay() {
        val local  = "# Card A\nbody a\n"
        val remote = "# Card B\nbody b\n"
        val result = ColumnMerge.mergeColumn(local = local, base = null, remote = remote)
        assertTrue(result.merged.contains("body a"), "local content preserved on null base")
    }

    @Test
    fun cleanMergeRoundTripsExact() {
        val base   = "# Card A\nbody a\n\n# Card B\nbody b\n"
        val local  = "# Card A\nbody a EDITED\n\n# Card B\nbody b\n"
        val remote = "# Card A\nbody a\n\n# Card B\nbody b EDITED\n"
        val expected = "# Card A\nbody a EDITED\n\n# Card B\nbody b EDITED\n"
        val result = ColumnMerge.mergeColumn(local, base, remote)
        assertFalse(result.hadConflict)
        assertEquals(expected, result.merged)   // byte-exact, no trimEnd
    }

    @Test
    fun conflictPreservesLocalSide() {
        val base   = "# Card A\nshared line\n"
        val local  = "# Card A\nshared line LOCAL\n"
        val remote = "# Card A\nshared line REMOTE\n"
        val result = ColumnMerge.mergeColumn(local, base, remote)
        assertTrue(result.hadConflict)
        assertTrue(result.merged.contains("shared line LOCAL"), "local content preserved in conflict output")
        assertFalse(result.merged.contains("shared line REMOTE"), "remote conflict side not silently included")
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    @Test
    fun nullBaseSupersetDoesNotDuplicateSharedContent() {
        // The reported bug: a column had cards appended locally; on a no-ancestor merge against the
        // pre-existing (shorter) remote, the shared prefix was duplicated. With an empty origin diff3
        // can't anchor on the common cards, so the whole column became one conflict region and the
        // union appended local-then-remote in full — duplicating everything they share.
        val remote = "# Card A\nbody a\n\n# Card B\nbody b\n"
        val local  = "# Card A\nbody a\n\n# Card B\nbody b\n\n# Card C\nappended\n"
        val result = ColumnMerge.mergeColumn(local = local, base = null, remote = remote)
        assertEquals(local, result.merged, "local superset: keep all, never duplicate the shared cards")
        assertEquals(1, occurrences(result.merged, "# Card A"), "shared card A not duplicated")
        assertEquals(1, occurrences(result.merged, "# Card B"), "shared card B not duplicated")
    }

    @Test
    fun nullBaseDivergentUnionsWithoutDuplicatingCommon() {
        // First-connect union of two devices that share a card but each added a different one: keep
        // BOTH unique cards, but the shared card must appear exactly once.
        val local  = "# Shared\nbody\n\n# Local\nlocal only\n"
        val remote = "# Shared\nbody\n\n# Remote\nremote only\n"
        val result = ColumnMerge.mergeColumn(local = local, base = null, remote = remote)
        assertTrue(result.merged.contains("local only"), "local-only card kept")
        assertTrue(result.merged.contains("remote only"), "remote-only card kept (first-connect union)")
        assertEquals(1, occurrences(result.merged, "# Shared"), "shared card not duplicated")
    }
}
