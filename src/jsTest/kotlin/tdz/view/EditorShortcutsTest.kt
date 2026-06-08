package tdz.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorShortcutsTest {

    private val lines = listOf(
        "# Top",          // 0
        "body a",         // 1
        "## Middle",      // 2
        "body b",         // 3
        "  ### Indented", // 4 (leading spaces still a heading)
        "body c",         // 5
    )

    @Test
    fun jumpsForwardToNextHeading() {
        assertEquals(2, headingRow(lines, fromRow = 1, forward = true))
        assertEquals(4, headingRow(lines, fromRow = 2, forward = true))
    }

    @Test
    fun jumpsBackwardToPreviousHeading() {
        assertEquals(2, headingRow(lines, fromRow = 3, forward = false))
        assertEquals(0, headingRow(lines, fromRow = 1, forward = false))
    }

    @Test
    fun returnsNullWhenNoHeadingInDirection() {
        assertNull(headingRow(lines, fromRow = 5, forward = true), "nothing after the last line")
        assertNull(headingRow(listOf("plain", "text"), fromRow = 0, forward = true), "no headings at all")
    }
}
