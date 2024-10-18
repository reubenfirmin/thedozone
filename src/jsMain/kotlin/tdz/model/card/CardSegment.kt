package tdz.model.card

/**
 * A segment of a card, i.e. one or more lines logically grouped by type.
 */
sealed interface CardSegment {

    fun isLink(): Boolean = false

    fun links(): List<LinkText> = listOf()
}
