package tdz.model.markdown

import tdz.model.ColumnId
import tdz.model.card.Blockquote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownBlockquoteTest {

    @Test
    fun consecutiveQuoteLinesFormOneBlockquote() {
        val cards = MarkdownService.fromMd("> hello\n> world\n", ColumnId(0))
        assertEquals(1, cards.size, "a blockquote should produce one card")
        val seg = cards.first().segments.single()
        assertTrue(seg is Blockquote, "quote lines should parse to a Blockquote, got $seg")
        assertEquals("hello\nworld", seg.text)
    }

    @Test
    fun quoteMarkerAndSingleSpaceAreStripped() {
        val cards = MarkdownService.fromMd("> indented:\n>   nested\n>plain\n", ColumnId(0))
        val bq = cards.first().segments.single() as Blockquote
        // one optional space after '>' is removed; further indentation is preserved
        assertEquals("indented:\n  nested\nplain", bq.text)
    }

    @Test
    fun emptyQuoteLineKeepsBlankInsideQuote() {
        val cards = MarkdownService.fromMd("> a\n>\n> b\n", ColumnId(0))
        val bq = cards.first().segments.single() as Blockquote
        assertEquals("a\n\nb", bq.text)
    }

    @Test
    fun textAfterQuoteJoinsTheSameCard() {
        // a non-quote line ends the blockquote but stays in the same card (no blank line between)
        val cards = MarkdownService.fromMd("> quote\nafter\n", ColumnId(0))
        assertEquals(1, cards.size)
        assertEquals(2, cards.first().segments.size, "blockquote + trailing text are two segments in one card")
        assertTrue(cards.first().segments.first() is Blockquote)
    }

    @Test
    fun blankLineEndsBlockquoteAndCard() {
        val cards = MarkdownService.fromMd("> quote\n\n# Next\n", ColumnId(0))
        assertEquals(2, cards.size, "a blank line after a blockquote starts a new card")
        assertTrue(cards.first().segments.single() is Blockquote)
    }

    @Test
    fun blockquoteRoundTripsThroughMarkdown() {
        val md = "> # Example\n> body line\n>\n> tail"
        val cards = MarkdownService.fromMd(md, ColumnId(0))
        val out = MarkdownService.toMd(cards, withMetadata = false).trim()
        // re-parse the output and confirm the blockquote text is preserved
        val reparsed = MarkdownService.fromMd(out, ColumnId(0)).first().segments.single() as Blockquote
        assertEquals("# Example\nbody line\n\ntail", reparsed.text)
    }
}
