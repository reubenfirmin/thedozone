package tdz.model.markdown

internal object MarkdownDiagram {

    fun consumeDiagram(trimmed: String, state: ParseState): Boolean {
        return if (trimmed == "---") {
            if (state.inDiagram()) {
                state.finishDiagram()
            } else {
                state.startDiagram()
            }
            true
        } else {
            if (state.inDiagram()) {
                state.appendToDiagram(trimmed)
                true
            } else {
                false
            }
        }
    }
}