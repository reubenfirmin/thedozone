package tdz.model.markdown

import tdz.model.ColumnId
import tdz.model.card.CardHeading
import tdz.model.card.CardText
import tdz.model.card.MapLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Storage-integrity contract: a save/reload cycle (toMd ∘ fromMd) must never corrupt content.
 * Each test here pins a way real text USED to get silently rewritten on first save.
 */
class MarkdownIntegrityTest {

    private val col = ColumnId("1")
    private fun roundTrip(md: String) = MarkdownService.toMd(MarkdownService.fromMd(md, col), false)

    @Test
    fun textContainingAMidWordHashIsNotAHeading() {
        val cards = MarkdownService.fromMd("Check PR#42 status\n", col)
        val segment = cards.single().segments.single()
        assertTrue(segment is CardText, "a mid-word # is plain text, not a heading (got $segment)")
        assertEquals("Check PR#42 status\n", roundTrip("Check PR#42 status\n"))
    }

    @Test
    fun actualHeadingsStillParse() {
        val cards = MarkdownService.fromMd("# Title\n## Sub\n", col)
        val levels = cards.flatMap { it.segments }.filterIsInstance<CardHeading>().map { it.level }
        assertEquals(listOf(1, 2), levels)
    }

    @Test
    fun mapLinkLabelSurvivesTheRoundTrip() {
        val md = "[map(40.5, -73.9) (My Location)]\n"
        val link = MarkdownService.fromMd(md, col).flatMap { it.segments }
            .filterIsInstance<CardText>().flatMap { it.text }
            .filterIsInstance<MapLink>().single()
        assertEquals("My Location", link.label, "the display label is parsed into the MapLink")
        assertTrue("(My Location)" in roundTrip(md), "the label survives a save")
    }

    @Test
    fun textAdjacentToALinkDoesNotDriftOnSave() {
        val md = "[https://x.com/a (a)]immediately after\n"
        assertEquals(md, roundTrip(md), "no space is invented between a link and adjacent text")
    }

    @Test
    fun spacedTextAroundLinksRoundTripsExactly() {
        val md = "before [https://x.com/a (a)] after\n"
        assertEquals(md, roundTrip(md))
    }
}
