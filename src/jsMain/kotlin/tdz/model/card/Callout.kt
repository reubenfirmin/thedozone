package tdz.model.card

data class Callout(
    override val text: List<RichText>,
    override val level: Int,
    override val type: ListMemberType,
    val weight: Int): RichTextSegment, ListMember