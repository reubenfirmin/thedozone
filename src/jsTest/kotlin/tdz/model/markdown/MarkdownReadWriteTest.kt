package tdz.model.markdown

import tdz.model.card.Callout
import tdz.model.card.CardText
import tdz.model.card.ListGroup
import tdz.model.card.MapLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for cards disappearing on a markdown round-trip. A card whose only content
 * is a list lived in listBuffer (not segmentsBuffer); the flush points finished the list only
 * AFTER gating on segmentsBuffer, so list-only cards were dropped.
 */
class MarkdownReadWriteTest {

    @Test
    fun singleItemListIsNotDropped() {
        val cards = MarkdownService.fromMd("* one\n", 0)
        assertEquals(1, cards.size, "a single-item list should produce one card")
        assertTrue(cards.first().segments.isNotEmpty(), "the card should retain the list segment")
    }

    @Test
    fun listSeparatedFromHeadingByBlankLineIsNotDropped() {
        val cards = MarkdownService.fromMd("# Title\n\n* one\n", 0)
        assertEquals(2, cards.size, "heading and a blank-line-separated list should both survive")
    }

    @Test
    fun headingThenListWithoutBlankLineIsOneCard() {
        val cards = MarkdownService.fromMd("# Title\n* one\n", 0)
        assertEquals(1, cards.size, "heading immediately followed by a list is a single card")
        assertEquals(2, cards.first().segments.size, "card holds the heading and the list group")
    }

    @Test
    fun arrowLineParsesAsCallout() {
        val cards = MarkdownService.fromMd("- heads up\n", 0)
        assertEquals(1, cards.size, "an arrow line should produce one card")
        val seg = cards.first().segments.single()
        assertTrue(seg is Callout, "an arrow line should parse to a Callout, got $seg")
    }

    @Test
    fun bulletLineParsesAsListGroupNotCallout() {
        val cards = MarkdownService.fromMd("* a task\n", 0)
        val seg = cards.first().segments.single()
        assertTrue(seg is ListGroup, "a bullet line should group into a ListGroup, got $seg")
    }

    @Test
    fun calloutWeightTracksDashCount() {
        val cards = MarkdownService.fromMd("-- strong callout\n", 0)
        val seg = cards.first().segments.single()
        assertTrue(seg is Callout, "got $seg")
        assertEquals(2, seg.weight, "two dashes is weight 2")
    }

    @Test
    fun calloutSurvivesRoundTrip() {
        val original = MarkdownService.fromMd("- heads up\n", 0)
        val md = MarkdownService.toMd(original, false)
        assertTrue(md.contains("- heads up"), "callout should write back to a dash line; got: '$md'")
        val reparsed = MarkdownService.fromMd(md, 0)
        assertTrue(
            reparsed.first().segments.single() is Callout,
            "reparsed segment should still be a Callout; md was: '$md'",
        )
    }

    @Test
    fun mapLinkParses() {
        val cards = MarkdownService.fromMd("[map(40.0, -70.0, 10, 300)]\n", 0)
        val seg = cards.first().segments.single() as CardText
        val link = seg.text.single()
        assertTrue(link is MapLink, "map(...) should parse to a MapLink, got $link")
        assertEquals(40.0, link.lat)
        assertEquals(-70.0, link.long)
        assertEquals(10, link.zoom)
        assertEquals(300, link.height)
    }

    @Test
    fun singleItemListSurvivesRoundTrip() {
        val original = MarkdownService.fromMd("* one\n", 0)
        val md = MarkdownService.toMd(original, false)
        val reparsed = MarkdownService.fromMd(md, 0)
        assertEquals(1, reparsed.size, "round-trip should preserve the list card; md was: '$md'")
        assertTrue(md.contains("one"), "written markdown should contain the item text; got: '$md'")
    }
}
