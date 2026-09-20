package tdz.model.markdown

import tdz.model.Card.Companion.taskItems
import tdz.model.ColumnId
import tdz.model.card.CardText
import tdz.model.card.ListGroup
import tdz.model.card.ListMemberType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ListIndentationTest {

    private val column = ColumnId("0")

    private fun assertRoundTrip(markdown: String, levels: List<Int>) {
        val cards = MarkdownService.fromMd(markdown, column)
        assertEquals(levels, cards.flatMap { it.taskItems() }.map { it.level })
        assertEquals(markdown, MarkdownService.toMd(cards, withMetadata = false),
            "saving must preserve every line and its indentation")
        assertEquals(cards, MarkdownService.fromMd(MarkdownService.toMd(cards, false), column))
    }

    @Test
    fun firstItemCanBeIndentedBeforeAnUnindentedSibling() {
        assertRoundTrip("  * First\n* Second\n", listOf(1, 0))
    }

    @Test
    fun listAfterPastedParagraphCanStartWithAnIndentedItem() {
        val markdown = "* First pasted line\nSecond pasted line\n  * Child\n* After\n"
        assertRoundTrip(markdown, listOf(0, 1, 0))
        val segments = MarkdownService.fromMd(markdown, column).single().segments
        assertIs<CardText>(segments[1])
        assertEquals(0, assertIs<ListGroup>(segments[2]).level)
    }

    @Test
    fun listAfterBlankLineCanReturnThroughSeveralIndentLevels() {
        assertRoundTrip("* Before\n\n      * Deep\n    * Middle\n  * Shallow\n* After\n",
            listOf(0, 3, 2, 1, 0))
    }

    @Test
    fun numberedListCanStartWithAnIndentedItem() {
        assertRoundTrip("  1. Nested\n1. First\n2. Second\n", listOf(1, 0, 0))
    }

    @Test
    fun mixedItemsKeepTheirTypesWhenFirstItemIsIndented() {
        val markdown = "  1. Nested number\n* Bullet\n"
        assertRoundTrip(markdown, listOf(1, 0))
        val card = MarkdownService.fromMd(markdown, column).single()
        assertEquals(ListMemberType.BULLET, assertIs<ListGroup>(card.segments.single()).type)
        val items = card.taskItems()
        assertEquals(listOf(ListMemberType.NUMBERED, ListMemberType.BULLET),
            items.map { it.type })
    }
}
