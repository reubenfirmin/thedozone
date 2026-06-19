package tdz.model

import tdz.model.ColumnId
import tdz.model.Card.Companion.cardsUnderH1
import tdz.model.card.Callout
import tdz.model.card.CardHeading
import tdz.model.card.CardText
import tdz.model.card.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardTest {

    private fun card(vararg segments: tdz.model.card.CardSegment) =
        Card(segments.toList(), CardMetadata(ColumnId("0")), 0)

    private fun h1(text: String) = card(CardHeading(text, 1))
    private fun text(text: String) = card(CardText(listOf(Text(text))))

    /** Title labels of the cards, for terse assertions. */
    private fun List<Card>.labels() = map { c ->
        (c.segments[0] as? CardHeading)?.text ?: (c.segments[0] as CardText).text.joinToString("") { (it as Text).text }
    }

    @Test
    fun singleH1IsH1Only() {
        assertTrue(card(CardHeading("Tracking", 1)).isH1Only(), "a lone H1 heading is an h1-only card")
    }

    @Test
    fun h1WithBodyIsNotH1Only() {
        assertFalse(
            card(CardHeading("Tracking", 1), CardText(listOf(Text("note")))).isH1Only(),
            "an H1 followed by body content is not h1-only",
        )
    }

    @Test
    fun h2OnlyIsNotH1Only() {
        assertFalse(card(CardHeading("Monday", 2)).isH1Only(), "a lone H2 is not an h1-only card")
    }

    @Test
    fun textOnlyIsNotH1Only() {
        assertFalse(card(CardText(listOf(Text("just text")))).isH1Only(), "a text card is not h1-only")
    }

    @Test
    fun emptyCardIsNotH1Only() {
        assertFalse(card().isH1Only(), "a card with no segments is not h1-only")
    }

    @Test
    fun cardsUnderH1StopsAtNextH1() {
        val column = listOf(h1("A"), text("a1"), text("a2"), h1("B"), text("b1"))
        assertEquals(listOf("a1", "a2"), column.cardsUnderH1(0).labels(), "group runs to the next h1")
    }

    @Test
    fun cardsUnderH1RunsToEndOfColumn() {
        val column = listOf(h1("A"), text("a1"), h1("B"), text("b1"), text("b2"))
        assertEquals(listOf("b1", "b2"), column.cardsUnderH1(2).labels(), "last group runs to the end")
    }

    @Test
    fun cardsUnderH1IsEmptyWhenNextCardIsH1() {
        val column = listOf(h1("A"), h1("B"), text("b1"))
        assertTrue(column.cardsUnderH1(0).isEmpty(), "an h1 immediately followed by another h1 has no children")
    }

    @Test
    fun cardsUnderH1IsEmptyForNonH1() {
        val column = listOf(h1("A"), text("a1"), text("a2"))
        assertTrue(column.cardsUnderH1(1).isEmpty(), "a non-h1 card has no header group")
    }

    @Test
    fun cardsUnderH1IsEmptyForOutOfRange() {
        assertTrue(listOf(h1("A")).cardsUnderH1(5).isEmpty(), "out-of-range index yields no children")
    }

}
