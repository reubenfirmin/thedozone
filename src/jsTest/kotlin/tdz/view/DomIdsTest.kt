package tdz.view

import tdz.model.CanvasName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DomIdsTest {

    @Test
    fun columnIdRoundTrips() {
        assertEquals(5, DomIds.columnId(DomIds.column(5)))
        assertEquals(0, DomIds.columnId(DomIds.column(0)))
    }

    @Test
    fun columnIdRejectsOtherFormats() {
        assertNull(DomIds.columnId(DomIds.card(1, 2)))
        assertNull(DomIds.columnId("column-"))
        assertNull(DomIds.columnId("column-abc"))
        assertNull(DomIds.columnId("not-an-id"))
    }

    @Test
    fun cardIdRoundTrips() {
        assertEquals(3 to 7, DomIds.cardId(DomIds.card(3, 7)))
    }

    @Test
    fun cardIdRejectsOtherFormats() {
        assertNull(DomIds.cardId(DomIds.column(3)))
        assertNull(DomIds.cardId("card-3"))
        assertNull(DomIds.cardId("card-3-x"))
        assertNull(DomIds.cardId("card-3-1-9"))
    }

    @Test
    fun isCardMatchesTheSelectorSemantics() {
        assertTrue(DomIds.isCard(DomIds.card(1, 0)))
        assertFalse(DomIds.isCard(DomIds.column(1)))
    }

    @Test
    fun canvasLinkRoundTrips() {
        assertEquals(CanvasName("work"), DomIds.canvasLinkName(DomIds.canvasLink(CanvasName("work"))))
    }

    @Test
    fun canvasLinkRejectsOtherFormats() {
        assertNull(DomIds.canvasLinkName(DomIds.column(1)))
        assertNull(DomIds.canvasLinkName(DomIds.card(1, 2)))
    }

    @Test
    fun editorIdIsColumnScoped() {
        assertEquals("editor-4", DomIds.editor(4))
    }
}
