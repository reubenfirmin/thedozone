 package tdz.model.markdown

import tdz.model.Card
import tdz.model.ColumnId
import tdz.model.CardMetadata
import tdz.model.card.*
import tdz.model.markdown.MarkdownBlockquote.consumeBlockquote
import tdz.model.markdown.MarkdownCardMetadata.consumeMetadataLine
import tdz.model.markdown.MarkdownCodeBlock.consumeCodeBlock
import tdz.model.markdown.MarkdownDiagram.consumeDiagram
import tdz.model.markdown.MarkdownHeading.consumeHeading
import tdz.model.markdown.MarkdownListElement.consumeCallout
import tdz.model.markdown.MarkdownListElement.consumeListElement
import tdz.model.markdown.MarkdownWhitespace.consumeEmptyLine
import tdz.model.markdown.MarkdownWhitespace.consumeSeparator

 /**
 * Markdown -> Cards (column assignment via embedded metadata comments)
 */
internal object MarkdownReader {

    val metadataLine = "[\\t ]*<!--([0-9]+):([0-9])-->".toRegex()

    /** Column-level style header (wallpaper/color) — skipped when parsing cards; see MarkdownService. */
    val columnStyleLine = "[\\t ]*<!--tdz-col:.*-->[\\t ]*".toRegex()

    /*

    Syntax

    # Optional column (or parent task) header

    ## Optional subtask header

    -- Task separator (or double newline)

    * Task step (maybe tabbed / spaced to indicate sub-step)
    N. Task step (maybe tabbed / spaced to indicate sub-step)

    ``` ... ``` code block

    [link (title)] link

    Descriptive text

     */

     /**
      * Parse this markdown which contains all columns on the canvas
      */
    fun parseAll(content: String): List<List<Card>> {
        return parse(content, ColumnId(0)).groupBy { it.metadata.columnId }.values.toList()
    }

     /**
      * Parse markdown, assigning cards to the specified column, until a new column editor tag is found, at which point
      * start using that.
      */
    fun parse(content: String, column: ColumnId): List<Card> {
        val state = ParseState(column)

        for (line in content.split("\n")) {
            val trimmed = line.trim()

            when {
                // note that these are order dependent
                columnStyleLine.matches(trimmed) -> {}   // column-level style header — not a card
                consumeCodeBlock(trimmed, line, state) -> {}
                consumeDiagram(trimmed, state) -> {}
                // before separator/empty-line: a blank line both ends the quote and the card
                consumeBlockquote(trimmed, line, state) -> {}
                consumeMetadataLine(trimmed, state) -> {}
                consumeSeparator(trimmed, state) -> {}
                consumeEmptyLine(trimmed, state) -> {}
                consumeListElement(line, state) -> {}
                consumeCallout(line, state) -> {}
                consumeHeading(trimmed, state) -> {}
                else -> {
                    // just rich text
                    state.appendGenericSegment(CardText(MarkdownLine(trimmed).elements()))
                }
            }
        }

        return state.finalize()
     }
}
