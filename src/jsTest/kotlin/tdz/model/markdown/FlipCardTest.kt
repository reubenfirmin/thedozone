package tdz.model.markdown

import tdz.model.ColumnId
import tdz.model.card.CardSideBreak
import tdz.model.card.CardText
import tdz.model.card.splitOnSideBreak
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A lone `//` line marks a FACE boundary inside a single card (a flip card): N-1 breaks ⇒ N sides.
 * It must NOT split the card (unlike a blank line / `==`), must survive a markdown round-trip, and
 * must stay literal inside a fenced code block.
 */
class FlipCardTest {

    @Test
    fun slashSlashBecomesASideBreakWithoutSplittingTheCard() {
        val cards = MarkdownService.fromMd("Question\n//\nAnswer\n", ColumnId("0"))
        assertEquals(1, cards.size, "a `//` is within-card, so it stays ONE card")
        val segs = cards.single().segments
        assertEquals(3, segs.size, "front text, the break, back text")
        assertTrue(segs[0] is CardText && segs[1] is CardSideBreak && segs[2] is CardText)
    }

    @Test
    fun splitOnSideBreakProducesNSidesForNMinusOneBreaks() {
        val cards = MarkdownService.fromMd("A\n//\nB\n//\nC\n", ColumnId("0"))
        val sides = cards.single().segments.splitOnSideBreak()
        assertEquals(3, sides.size, "two breaks ⇒ three sides")
        assertEquals(listOf(1, 1, 1), sides.map { it.size }, "each side holds its own segment")
    }

    @Test
    fun noBreakIsASingleSide() {
        val cards = MarkdownService.fromMd("Just one side\n", ColumnId("0"))
        assertEquals(1, cards.single().segments.splitOnSideBreak().size, "no `//` ⇒ one side")
    }

    @Test
    fun sideBreakRoundTripsThroughTheWriter() {
        val md = "Question\n//\nAnswer\n"
        val cards = MarkdownService.fromMd(md, ColumnId("0"))
        val out = MarkdownService.toMd(cards, withMetadata = false)
        assertTrue(out.lineSequence().any { it.trim() == "//" }, "writer must emit the `//` boundary; got:\n$out")
        // And re-parsing the output preserves the single multi-side card.
        val reparsed = MarkdownService.fromMd(out, ColumnId("0"))
        assertEquals(1, reparsed.size)
        assertEquals(3, reparsed.single().segments.size)
    }

    @Test
    fun slashSlashInsideACodeBlockStaysLiteral() {
        val cards = MarkdownService.fromMd("```\n//\n```\n", ColumnId("0"))
        val segs = cards.single().segments
        assertTrue(segs.none { it is CardSideBreak }, "a `//` inside a fenced block is code, not a face boundary")
    }
}
