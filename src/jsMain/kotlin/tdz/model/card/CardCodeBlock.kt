package tdz.model.card

data class CardCodeBlock(val text: String): CardSegment, RichText {

    override fun toMarkdown(): String {
        if (text.contains("\n")) {
            return "```\n$text\n```"
        }
        return "`$text`"
    }

    // TODO This is inefficient
    fun code() = text

    fun className() = "microlight"
}
