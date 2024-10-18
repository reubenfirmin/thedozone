package tdz.model.markdown

import tdz.model.Card
import tdz.model.CardMetadata

object MarkdownWhitespace {

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