package tdz.view

import tdz.model.markdown.MarkdownService
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
}
