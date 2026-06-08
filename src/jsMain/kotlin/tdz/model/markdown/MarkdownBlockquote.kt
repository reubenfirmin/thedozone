package tdz.model.markdown

internal object MarkdownBlockquote {

    /**
     * Accumulates consecutive `>`-prefixed lines into a single blockquote. Unlike a fenced code
     * block there is no closing delimiter: the first non-`>` line ends the quote. That breaking
     * line is NOT consumed here (returns false) so the normal dispatch can still handle it — a
     * blank line will both close the quote and the card, while plain text just continues the card.
     */
    fun consumeBlockquote(trimmed: String, line: String, state: ParseState): Boolean {
        return when {
            trimmed.startsWith(">") -> {
                if (!state.inBlockquote()) {
                    state.startBlockquote()
                }
                state.appendBlockquote(content(line))
                true
            }
            state.inBlockquote() -> {
                state.finishBlockquote()
                false
            }
            else -> false
        }
    }

    /** Strip the leading `>` marker and a single following space, preserving any further indent. */
    private fun content(line: String): String {
        val afterMarker = line.trimStart().removePrefix(">")
        return if (afterMarker.startsWith(" ")) afterMarker.substring(1) else afterMarker
    }
}
