package tdz.model.markdown

internal object MarkdownCodeBlock {

    fun consumeCodeBlock(trimmed: String, line: String, state: ParseState): Boolean {
        return if (trimmed == "```") {
            if (state.inCodeBlock()) {
                state.finishCode()
            } else {
                state.startCode()
            }
            true
        } else {
            if (state.inCodeBlock()) {
                // add the untrimmed to preserve whitespace
                state.appendCode(line)
                true
            } else {
                false
            }
        }
    }
}