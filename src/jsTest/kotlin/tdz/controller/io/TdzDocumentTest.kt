package tdz.controller.io

import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.ColumnLayoutSpec
import tdz.model.Split
import tdz.model.markdown.MarkdownService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TdzDocumentTest {

    private val canvas = Canvas(
        CanvasName("test"),
        CanvasName("_"),
        listOf(0, 1),
        listOf(ColumnLayoutSpec(0, Split.VERTICAL, listOf(1))),
    )

    @Test
    fun roundTripPreservesCanvasStructure() {
        val cards = MarkdownService.fromMd("# Hello\n", 1)
        val decoded = TdzDocument.decode(TdzDocument.encode(canvas, cards))
        assertEquals(canvas, decoded.prototype, "prototype should survive an encode/decode round-trip")
    }

    @Test
    fun roundTripPreservesCards() {
        val cards = MarkdownService.fromMd("# Hello\n\n* one\n* two\n", 1)
        val decoded = TdzDocument.decode(TdzDocument.encode(canvas, cards))
        assertEquals(
            MarkdownService.toMd(cards),
            MarkdownService.toMd(decoded.cards),
            "cards should serialize identically after a round-trip",
        )
    }

    @Test
    fun headerlessContentDecodesToNullPrototype() {
        val decoded = TdzDocument.decode("# just markdown\n")
        assertNull(decoded.prototype, "plain markdown has no canvas header")
        assertTrue(decoded.cards.isNotEmpty(), "the markdown body should still parse into cards")
    }

    @Test
    fun malformedHeaderFallsBackToMarkdownOnly() {
        // Starts with '{' but is not valid JSON: must not throw, must be treated as header-less.
        val decoded = TdzDocument.decode("{ not valid json\n# hi\n")
        assertNull(decoded.prototype, "an unparseable header yields no prototype")
    }

    @Test
    fun emptyCardsRoundTrip() {
        val decoded = TdzDocument.decode(TdzDocument.encode(canvas, emptyList()))
        assertEquals(canvas, decoded.prototype)
        assertTrue(decoded.cards.isEmpty(), "no cards in, no cards out")
    }
}
