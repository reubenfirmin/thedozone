package tdz.model.markdown

import tdz.model.Card
import tdz.model.ColumnStyle
import kotlin.js.Json
import kotlin.js.json

object MarkdownService {

    private val markdownReader = MarkdownReader
    private val markdownWriter = MarkdownWriter

    fun toMd(cards: List<Card>, withMetadata: Boolean = true) = markdownWriter.generate(cards, withMetadata)

    fun fromMd(markdown: String, column: Int) = markdownReader.parse(markdown, column)

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
}
