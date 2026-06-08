package tdz.view

import tdz.model.CanvasName
import tdz.model.card.CanvasLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasSwitcherTest {

    private fun link(name: String, label: String) = CanvasLink(CanvasName(name), label)

    @Test
    fun buildsNumberedItemsWiredToSelect() {
        val picked = mutableListOf<CanvasName>()
        val sections = childBoardSections(listOf(link("alpha", "Alpha"), link("beta", "Beta"))) { picked += it }
        val items = sections.flatMap { it.items }
        assertEquals(listOf("1. Alpha", "2. Beta"), items.map { it.label }, "items are numbered with their labels")
        assertTrue(items.all { it.enabled }, "board entries are selectable")
        items[1].onSelect()
        assertEquals(listOf(CanvasName("beta")), picked, "selecting the second item fires for that board")
    }

    @Test
    fun emptyLinksProducesNoItems() {
        assertTrue(childBoardSections(emptyList()) { }.isEmpty(), "no child boards => no menu sections")
    }

    @Test
    fun numberKeysMapToBoardIndexesWithinRange() {
        assertEquals(0, switcherIndexForKey("1", 3))
        assertEquals(2, switcherIndexForKey("3", 3))
        assertEquals(null, switcherIndexForKey("4", 3), "out of range => no selection")
        assertEquals(null, switcherIndexForKey("0", 3), "0 has no board")
        assertEquals(null, switcherIndexForKey("a", 3), "non-digit => no selection")
    }
}
