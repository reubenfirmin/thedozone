package tdz.model.markdown

import tdz.model.Card
import tdz.model.card.*

internal object MarkdownWriter {

    /** Mirror of CardRenderer's recursion ceiling: a malformed or cyclic group must not loop
     *  unboundedly on save (which would freeze the tab and lose the write). */
    private const val MAX_WRITE_LIST_DEPTH = 32

    /** Ordinal state for numbered lists — created per [generate] call, never shared across calls. */
    private class Numbering {
        var count = 1
    }

    fun generate(cards: List<Card>, withMetadata: Boolean = false): String {
        val output = StringBuilder()
        val numbering = Numbering()

        cards.forEachIndexed { index, card ->
            if (withMetadata) {
                // The ":0" width slot is vestigial but kept: the metadata regex expects two groups
                // and every stored/synced file carries it — dropping it is a format change.
                output.append("<!--${card.metadata.columnId}:0-->\n")
            }
            var lastSegment: CardListItem? = null
            for (segment in card.segments) {
                // Exhaustive over the sealed CardSegment: the SAVE path must serialize every
                // subtype, so a new one is a compile error here rather than silent data loss.
                output.append(when(segment) {
                    // Defensive branch required by exhaustiveness: the parser groups list items into a
                    // ListGroup, so a bare CardListItem at top level isn't normally produced — but it's a
                    // sealed subtype and must serialize to something rather than be dropped.
                    is CardListItem -> {
                        val result = listItemLine(segment, lastSegment, numbering)
                        lastSegment = segment
                        result
                    }
                    // The parser groups list items into a ListGroup; render each member as a line
                    // (recursively for nested groups). Without this, writing a list card threw.
                    is ListGroup -> renderListGroup(segment, numbering)
                    is CardHeading -> "${"#".repeat(segment.level)}${segment.text}"
                    is CardCodeBlock -> segment.toMarkdown()
                    is Blockquote -> segment.toMarkdown()
                    is CardText -> renderRichText(segment.text)
                    is Callout -> "${"  ".repeat(segment.level)}${"-".repeat(segment.weight)} ${renderRichText(segment.text)}"
                    is CardDiagram -> segment.toMarkdown()
                    // A flip card's face boundary — round-trips back to the `//` that produced it.
                    is CardSideBreak -> "//"
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
    private fun renderListGroup(group: ListGroup, numbering: Numbering): String {
        val lines = mutableListOf<String>()
        var lastItem: CardListItem? = null
        fun walk(g: ListGroup, depth: Int) {
            // Match the renderer's guard: a malformed/cyclic group must not infinite-loop on save
            // (which would freeze the tab and lose the write) any more than it may on render.
            if (depth > MAX_WRITE_LIST_DEPTH) return
            for (member in g.members) {
                when (member) {
                    is CardListItem -> {
                        lines.add(listItemLine(member, lastItem, numbering))
                        lastItem = member
                    }
                    is ListGroup -> walk(member, depth + 1)
                    else -> {}
                }
            }
        }
        walk(group, 0)
        return lines.joinToString("\n")
    }

    private fun listItemLine(segment: CardListItem, lastSegment: CardListItem?, numbering: Numbering) =
        "${"  ".repeat(segment.level)}${bulletText(segment, lastSegment, numbering)} ${doneText(segment)}${renderRichText(segment.text)}"

    private fun renderRichText(values: List<RichText>): String =
        // Verbatim, no separator: Text tokens carry their own spacing, so trimming-and-rejoining
        // invented a space between a link and immediately-adjacent text on every save.
        values.joinToString("") { it.toMarkdown() }

    private fun bulletText(segment: CardListItem, lastSegment: CardListItem?, numbering: Numbering) = when(segment.type) {
        ListMemberType.BULLET -> {
            numbering.count = 1
            "*"
        }
        ListMemberType.NUMBERED -> {
            if (lastSegment != null && segment.level == lastSegment.level) {
                numbering.count++
            } else {
                numbering.count = 1
            }

            "${numbering.count}."
        }
    }

    private fun doneText(segment: CardListItem) = if (segment.done) DONE_MARKER else ""
}