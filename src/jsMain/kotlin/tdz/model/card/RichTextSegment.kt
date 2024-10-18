package tdz.model.card

/**
 * A segment that has rich text.
 */
interface RichTextSegment: CardSegment {

    val text: List<RichText>

    override fun isLink() = links().isNotEmpty()

    override fun links() = text.filterIsInstance<LinkText>()
}