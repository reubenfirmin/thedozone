package tdz.model.markdown

internal object MarkdownDiagram {

    fun consumeDiagram(trimmed: String, line: String, state: ParseState): Boolean {
        return if (trimmed == "---") {
            if (state.inDiagram()) {
                state.finishDiagram()
            } else {
                state.startDiagram()
            }
            true
        } else {
            if (state.inDiagram()) {
                // append the untrimmed line to preserve indentation (mirrors consumeCodeBlock)
                state.appendToDiagram(line)
                true
            } else {
                false
            }
        }
    }
}