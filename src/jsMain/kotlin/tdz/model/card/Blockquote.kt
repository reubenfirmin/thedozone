package tdz.model.card

/**
 * A blockquote: one or more consecutive `> `-prefixed lines, held as raw text. Non-interactive
 * (like [CardCodeBlock]); its contents are not re-parsed as Markdown. Rendered as a quote box with
 * a copy button, so it doubles as a copyable snippet.
 */
data class Blockquote(val text: String) : CardSegment, RichText {

    override fun toMarkdown(): String =
        text.split("\n").joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
}
