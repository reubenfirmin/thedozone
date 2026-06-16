package tdz.model.markdown

import tdz.model.Card
import tdz.model.CardMetadata
import tdz.model.card.CardSideBreak

object MarkdownWhitespace {

    /**
     * A lone `//` line is a card-internal FACE boundary: append the marker WITHOUT closing the card
     * (so the segments before and after stay one card with multiple sides). Checked after the
     * code-block/diagram/blockquote consumers, so a `//` inside those stays literal content.
     */
    fun consumeSideBreak(trimmed: String, state: ParseState): Boolean {
        return if (trimmed == "//") {
            state.appendGenericSegment(CardSideBreak)
            true
        } else {
            false
        }
    }

    fun consumeSeparator(trimmed: String, state: ParseState): Boolean {
        return if (trimmed.startsWith("==") || (trimmed.isEmpty() && state.prevNewline)) {
            state.createCardFromSegments()
            true
        } else {
            false
        }
    }

    fun consumeEmptyLine(trimmed: String, state: ParseState): Boolean {
        return if (trimmed.isEmpty()) {
            state.createCardFromSegments()
//            state.prevNewline = true
            true
        } else {
//            state.prevNewline = false
            false
        }
    }
}