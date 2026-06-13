package tdz.model.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The editor-save artifact: hitting enter on the last bullet leaves a bare marker ("* ") that the
 * parser rejects as a list item and renders as a stray text line. Saving strips the trailing run —
 * and ONLY the trailing run; everything else round-trips identically.
 */
class StripTrailingEmptyListItemsTest {

    private fun strip(md: String) = MarkdownService.stripTrailingEmptyListItems(md)

    @Test fun dropsATrailingEmptyBullet() =
        assertEquals("# T\n* item\n", strip("# T\n* item\n* \n"))

    @Test fun dropsATrailingRunIncludingNestedAndNumbered() =
        assertEquals("# T\n* item\n", strip("# T\n* item\n  * \n1. \n"))

    @Test fun dropsATrailingEmptyDoneMarker() =
        assertEquals("# T\n* item\n", strip("# T\n* item\n* /\n"))

    @Test fun dropsBlankLinesStrandedBehindTheDroppedMarker() =
        assertEquals("# T\n* item\n", strip("# T\n* item\n\n* \n"))

    @Test fun identityWhenNothingDangles() {
        for (md in listOf(
            "# T\n* item\n",
            "# T\n* item",                  // no trailing newline
            "# T\n* \n* item\n",            // empty marker ABOVE content stays
            "# A\n\n# B\n",                 // blank card separators stay
            "notes\n---\n",                 // a dash divider is not a list-save artifact
            "",
        )) assertEquals(md, strip(md), "must be identity for <<$md>>")
    }
}
