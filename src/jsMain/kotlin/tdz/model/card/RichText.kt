package tdz.model.card

/**
 * A subcomponent of a segment that may be plain or formatted text.
 */
interface RichText {

    fun toMarkdown(): String
}
