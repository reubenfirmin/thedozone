package tdz.view

import tdz.model.CardRef
import tdz.model.ColumnId
import tdz.model.CanvasName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DomIdsTest {

    @Test
    fun columnIdRoundTrips() {
        assertEquals(ColumnId("5"), DomIds.columnId(DomIds.column(ColumnId("5"))))
        assertEquals(ColumnId("0"), DomIds.columnId(DomIds.column(ColumnId("0"))))
        // Opaque alphanumeric ids round-trip too (the new allocation format).
        assertEquals(ColumnId("aB3xK9"), DomIds.columnId(DomIds.column(ColumnId("aB3xK9"))))
    }

    @Test
    fun columnIdRejectsOtherFormats() {
        assertNull(DomIds.columnId(DomIds.card(CardRef(ColumnId("1"), 2))))  // a card id, not a column id
        assertNull(DomIds.columnId("column-"))                              // empty id after the prefix
        assertNull(DomIds.columnId("not-an-id"))                            // wrong prefix
    }

    @Test
    fun cardIdRoundTrips() {
        assertEquals(CardRef(ColumnId("3"), 7), DomIds.cardId(DomIds.card(CardRef(ColumnId("3"), 7))))
    }

    @Test
    fun cardIdRejectsOtherFormats() {
        assertNull(DomIds.cardId(DomIds.column(ColumnId("3"))))
        assertNull(DomIds.cardId("card-3"))
        assertNull(DomIds.cardId("card-3-x"))
        assertNull(DomIds.cardId("card-3-1-9"))
    }

    @Test
    fun isCardMatchesTheSelectorSemantics() {
        assertTrue(DomIds.isCard(DomIds.card(CardRef(ColumnId("1"), 0))))
        assertFalse(DomIds.isCard(DomIds.column(ColumnId("1"))))
    }

    @Test
    fun canvasLinkRoundTrips() {
        assertEquals(CanvasName("work"), DomIds.canvasLinkName(DomIds.canvasLink(CanvasName("work"))))
    }

    @Test
    fun canvasLinkRejectsOtherFormats() {
        assertNull(DomIds.canvasLinkName(DomIds.column(ColumnId("1"))))
        assertNull(DomIds.canvasLinkName(DomIds.card(CardRef(ColumnId("1"), 2))))
    }

    @Test
    fun editorIdIsColumnScoped() {
        assertEquals("editor-4", DomIds.editor(ColumnId("4")))
    }
}
