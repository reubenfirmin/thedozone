package tdz.view

import tdz.model.card.Blockquote
import tdz.model.markdown.MarkdownService
import tdz.view.help.HelpContent
import tdz.view.help.HelpTopic
import kotlin.test.Test
import kotlin.test.assertTrue

class HelpContentTest {
    @Test
    fun `every help topic parses to at least one card`() {
        HelpTopic.values().forEach { topic ->
            val cards = MarkdownService.fromMd(topic.markdown).flatten()
            assertTrue(cards.isNotEmpty(), "${topic.name} produced no cards")
        }
    }

    @Test
    fun `formatting guide renders its examples as blockquotes`() {
        val segments = MarkdownService.fromMd(HelpContent.FORMATTING).flatten().flatMap { it.segments }
        val quotes = segments.filterIsInstance<Blockquote>()
        assertTrue(quotes.size >= 8, "expected the formatting guide's examples to be blockquotes, found ${quotes.size}")
        // the code example must survive verbatim inside a quote (triple-backticks preserved as text)
        assertTrue(quotes.any { it.text.contains("fun main()") }, "code example should be a blockquote")
    }
}
