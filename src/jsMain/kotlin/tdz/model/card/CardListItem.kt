package tdz.model.card

/**
 * A list item, either bulleted, arrowed or numeric
 * @param level 0 indexed
 */
data class CardListItem(override val text: List<RichText>,
                        override val level: Int,
                        override val type: ListMemberType,
                        val weight: Int,
                        val done: Boolean):
    RichTextSegment, ListMember