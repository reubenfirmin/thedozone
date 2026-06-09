package tdz.model.markdown

import tdz.model.Card
import tdz.model.card.*

internal object MarkdownWriter {

    private var count = 1

    fun generate(cards: List<Card>, withMetadata: Boolean = false): String {
        val output = StringBuilder()

        cards.forEachIndexed { index, card ->
            if (withMetadata) {
                // TODO remove width (although maybe we want to store other)
                output.append("<!--${card.metadata.columnId}:0-->\n")
            }
            var lastSegment: CardListItem? = null
            for (segment in card.segments) {
                output.append(when(segment) {
                    is CardListItem -> {
                        val result =
                            // TODO move to function
                            "${"  ".repeat(segment.level)}${bulletText(segment, lastSegment)} ${doneText(segment)}${renderRichText(segment.text)}"
                        lastSegment = segment
                        result
                    }
                    // The parser groups list items into a ListGroup; render each member as a line
                    // (recursively for nested groups). Without this, writing a list card threw.
                    is ListGroup -> renderListGroup(segment)
                    is CardHeading -> "${"#".repeat(segment.level)}${segment.text}"
                    is CardCodeBlock -> segment.toMarkdown()
                    is Blockquote -> segment.toMarkdown()
                    is CardText -> renderRichText(segment.text)
                    is Callout -> "${"  ".repeat(segment.level)}${"-".repeat(segment.weight)} ${renderRichText(segment.text)}"
                    is CardDiagram -> "---\n${segment.text}\n---"
                    is IgnoreMe -> ""
                    else -> throw Exception("Not supported: $segment")
                })
                output.append("\n")
            }
            if (index < cards.size - 1) {
                output.append("\n") // end of card
            }
        }
        return output.toString()
    }

    /** Render a list group to markdown lines, mirroring the parser's grouping (recurses into subgroups). */
    private fun renderListGroup(group: ListGroup): String {
        val lines = mutableListOf<String>()
        var lastItem: CardListItem? = null
        fun walk(g: ListGroup) {
            for (member in g.members) {
                when (member) {
                    is CardListItem -> {
                        lines.add("${"  ".repeat(member.level)}${bulletText(member, lastItem)} ${doneText(member)}${renderRichText(member.text)}")
                        lastItem = member
                    }
                    is ListGroup -> walk(member)
                    else -> {}
                }
            }
        }
        walk(group)
        return lines.joinToString("\n")
    }

    private fun renderRichText(values: List<RichText>): String {
        return values.joinToString(" ") { text ->
            // TODO extra space was being added around links, not sure why
            text.toMarkdown().trim()
        }
    }

    private fun bulletText(segment: CardListItem, lastSegment: CardListItem?) = when(segment.type) {
        ListMemberType.BULLET -> {
            count = 1
            "*"
        }
        ListMemberType.NUMBERED -> {
            if (lastSegment != null && segment.level == lastSegment.level) {
                count ++
            } else {
                count = 1
            }

            "$count."
        }
        ListMemberType.ARROW -> {
            count = 1
            "-"
        }
    }

    private fun doneText(segment: CardListItem) = if (segment.done) {
        "/" // TODO constant
    } else {
        ""
    }
}