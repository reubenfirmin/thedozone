package tdz.model.markdown

import tdz.model.Card
import tdz.model.ColumnId
import tdz.model.ColumnStyle
import kotlin.js.Json
import kotlin.js.json

object MarkdownService {

    private val markdownReader = MarkdownReader
    private val markdownWriter = MarkdownWriter

    fun toMd(cards: List<Card>, withMetadata: Boolean = true) = markdownWriter.generate(cards, withMetadata)

    fun fromMd(markdown: String, column: ColumnId) = markdownReader.parse(markdown, column)

    fun fromMd(markdown: String) = markdownReader.parseAll(markdown)

    // --- column-level style header ---
    // Persisted as a single comment line at the very top of a column's markdown, so it travels with
    // the column file (and therefore syncs) exactly like card content does:
    //   <!--tdz-col:{"wallpaper":"https://…","color":"#fff"}-->
    private const val PREFIX = "<!--tdz-col:"
    private const val SUFFIX = "-->"

    /** The header line (with trailing newline) for [style], or "" when there's nothing to record. */
    fun columnStyleHeader(style: ColumnStyle): String {
        if (style.wallpaper.isNullOrBlank() && style.color.isNullOrBlank()) return ""
        val j = json("wallpaper" to (style.wallpaper ?: ""), "color" to (style.color ?: ""))
        return "$PREFIX${JSON.stringify(j)}$SUFFIX\n"
    }

    /** Read the column style from a column's markdown (blanks when absent/malformed). */
    fun readColumnStyle(markdown: String): ColumnStyle {
        val line = markdown.lineSequence().firstOrNull { it.trimStart().startsWith(PREFIX) }
            ?: return ColumnStyle(null, null)
        val raw = line.trim().removePrefix(PREFIX).removeSuffix(SUFFIX)
        return try {
            val j = JSON.parse<Json>(raw)
            ColumnStyle((j["wallpaper"] as? String)?.ifBlank { null }, (j["color"] as? String)?.ifBlank { null })
        } catch (e: Throwable) {
            ColumnStyle(null, null)
        }
    }

    /** The markdown with any column-style header removed (the card body alone). */
    fun stripColumnStyle(markdown: String): String =
        markdown.lineSequence().filterNot { it.trimStart().startsWith(PREFIX) }.joinToString("\n").trimStart('\n')

    // A list marker with no text (optionally just the done "/"): not a list item to the parser
    // (MarkdownListElement returns null for empty text), so it renders as a stray text line.
    // Mirrors the reader's bullet/numbered syntax; dash lines are excluded — a bare "---" may be
    // someone's deliberate visual divider.
    private val emptyListMarker = Regex("[\\t ]*([*|+]+|[0-9]*\\.)[\\t ]*(/[\\t ]*)?")

    /**
     * Drop empty list markers a save left dangling at the END of the column — the "I hit enter on
     * the last bullet and saved" artifact. Only the trailing run goes; blank lines stranded behind
     * it go with it; everything else (including final-newline presence) is untouched, so this is
     * the identity for any content that doesn't end in an empty marker.
     */
    fun stripTrailingEmptyListItems(markdown: String): String {
        val endsWithNewline = markdown.endsWith("\n")
        val lines = markdown.split("\n").toMutableList()
        if (endsWithNewline) lines.removeAt(lines.size - 1)   // split artifact after the final \n
        var dropped = false
        while (true) {
            val last = lines.indexOfLast { it.isNotBlank() }
            if (last < 0 || !emptyListMarker.matches(lines[last])) break
            lines.removeAt(last)
            dropped = true
        }
        if (!dropped) return markdown
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        return if (lines.isEmpty()) "" else lines.joinToString("\n") + if (endsWithNewline) "\n" else ""
    }
}
