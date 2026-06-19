package tdz.model.card

/**
 * A blockquote: one or more consecutive `> `-prefixed lines, held as raw text. Non-interactive
 * (like [CardCodeBlock]); its contents are not re-parsed as Markdown. Rendered as a quote box with
 * a copy button, so it doubles as a copyable snippet.
 *
 * Dual supertype: the parser only ever emits a Blockquote as a top-level [CardSegment] (never nested
 * inside a [CardText]'s inline [RichText] list), but the view renders it through its [RichText] path
 * (`toMarkdown` + a shared quote-box renderer), so the [RichText] role is load-bearing there.
 */
data class Blockquote(val text: String) : CardSegment, RichText {

    override fun toMarkdown(): String =
        text.split("\n").joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
}
