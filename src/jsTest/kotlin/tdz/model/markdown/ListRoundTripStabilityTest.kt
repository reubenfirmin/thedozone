package tdz.model.markdown

import tdz.model.ColumnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A column's markdown must be a FIXED POINT under repeated parse→serialize: every sync/save round
 * trips the bytes, so if nesting depth (or anything) grows by a notch each cycle, a long-lived,
 * frequently-saved column drifts until it eventually blows the render recursion — the
 * "Maximum call stack size exceeded" that took down a board. This guards the round trip.
 */
class ListRoundTripStabilityTest {

    private fun roundTrip(md: String): String =
        MarkdownService.toMd(MarkdownService.fromMd(md, ColumnId("0")), withMetadata = false)

    private fun assertStable(md: String) {
        val once = roundTrip(md)
        val twice = roundTrip(once)
        assertEquals(once, twice, "second round trip must be identical to the first:\n<<$once>>\nvs\n<<$twice>>")
        // And depth must not creep across many cycles.
        var cur = once
        repeat(50) { cur = roundTrip(cur) }
        assertEquals(once, cur, "50 round trips must not drift:\n<<$once>>\nvs\n<<$cur>>")
        assertTrue(cur.lines().none { it.length > md.length + 80 },
            "no line grew unboundedly (runaway indentation): <<$cur>>")
    }

    @Test fun flatBullets() = assertStable("* a\n* b\n* c\n")
    @Test fun nestedBullets() = assertStable("* a\n  * b\n    * c\n")
    @Test fun doneNested() = assertStable("* /a\n  * b\n  * /c\n")
    @Test fun numberedNested() = assertStable("1. a\n  1. b\n  2. c\n")
    @Test fun mixedHeadingsAndLists() = assertStable("# H1\n* a\n  * b\n\n## H2\n* x\n  * /y\n")
    @Test fun deepNesting() = assertStable("* a\n  * b\n    * c\n      * d\n        * e\n")
}
