package tdz.model.markdown

import tdz.model.card.CardHeading

internal object MarkdownHeading {

    fun consumeHeading(trimmed: String, state: ParseState): Boolean {
        val heading = cardHeading(trimmed)
        return if (heading != null) {
            state.appendGenericSegment(heading)
            true
        } else {
            false
        }
    }

    private fun cardHeading(line: String): CardHeading? {
        // Anchored: a heading is a line STARTING with # — a mid-word # (PR#42) is plain text.
        val regex = "([#]+)(.*)".toRegex()
        val result = regex.matchEntire(line)
        if (result != null) {
            return CardHeading(result.groupValues[2], result.groupValues[1].length)
        }
        return null
    }
}