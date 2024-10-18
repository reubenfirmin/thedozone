package tdz.model.markdown

import tdz.model.Card

object MarkdownService {

    private val markdownReader = MarkdownReader
    private val markdownWriter = MarkdownWriter

    fun toMd(cards: List<Card>, withMetadata: Boolean = true) = markdownWriter.generate(cards, withMetadata)

    fun fromMd(markdown: String, column: Int) = markdownReader.parse(markdown, column)

    fun fromMd(markdown: String) = markdownReader.parseAll(markdown)
}