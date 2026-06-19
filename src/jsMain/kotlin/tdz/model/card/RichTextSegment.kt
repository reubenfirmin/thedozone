package tdz.model.card

/**
 * A segment that has rich text. Sealed so the writer's `when` over [CardSegment] can enumerate its
 * leaves ([CardText], [CardListItem], [Callout]) and stay exhaustive — a new rich-text segment is a
 * compile error on the save path, not silent data loss.
 */
sealed interface RichTextSegment: CardSegment {

    val text: List<RichText>

    override fun isLink() = links().isNotEmpty()

    override fun links() = text.filterIsInstance<LinkText>()
}