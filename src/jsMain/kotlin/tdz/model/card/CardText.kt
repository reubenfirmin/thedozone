package tdz.model.card

/**
 * A text segment, made up of a sequence of RichText (which may contain links)
 */
data class CardText(override val text: List<RichText>): RichTextSegment
