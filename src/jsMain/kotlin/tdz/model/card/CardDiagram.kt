package tdz.model.card

data class CardDiagram(val text: String): CardSegment {

    /** Round-trips back to the `---`-fenced block the parser consumed to produce it. */
    fun toMarkdown(): String = "---\n$text\n---"
}