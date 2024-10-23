package tdz.model.card

/**
 * A subcomponent of a segment that may be plain or formatted text.
 */
sealed interface RichText {

    fun toMarkdown(): String
}
