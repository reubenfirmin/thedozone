package tdz.model.card

/**
 * A non-interactive arrow callout, parsed from a dash-prefixed line (`- text`, `-- text`, ...).
 * Unlike a list item it has no checkbox / done state. [weight] is the dash count (arrow style);
 * [level] is the indent depth.
 */
data class Callout(
    override val text: List<RichText>,
    val level: Int,
    val weight: Int,
) : RichTextSegment
