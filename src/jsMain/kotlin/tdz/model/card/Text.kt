package tdz.model.card

/**
 * A pure section of text, part of a CardText segment.
 */
data class Text(val text: String): RichText {

    override fun toMarkdown() = text
}
