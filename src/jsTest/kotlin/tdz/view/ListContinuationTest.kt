package tdz.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListContinuationTest {

    @Test
    fun continuesBulletMarkers() {
        assertEquals("* ", listContinuation("* foo")?.nextMarker)
        assertEquals("- ", listContinuation("- bar")?.nextMarker)
        assertEquals("+ ", listContinuation("+ baz")?.nextMarker)
    }

    @Test
    fun carriesContentAndIndent() {
        val c = listContinuation("  * nested item")
        assertEquals("  ", c?.indent)
        assertEquals("* ", c?.nextMarker)
        assertEquals("nested item", c?.content)
    }

    @Test
    fun incrementsNumbered() {
        val c = listContinuation("3. third")
        assertEquals("4. ", c?.nextMarker)
        assertEquals("third", c?.content)
    }

    @Test
    fun emptyItemHasEmptyContent() {
        // drives the "exit the list" branch
        assertEquals("", listContinuation("* ")?.content)
        assertEquals("", listContinuation("  1. ")?.content)
    }

    @Test
    fun nonListLinesAreNull() {
        assertNull(listContinuation("just text"))
        assertNull(listContinuation("*nospace"))
        assertNull(listContinuation(""))
    }
}
