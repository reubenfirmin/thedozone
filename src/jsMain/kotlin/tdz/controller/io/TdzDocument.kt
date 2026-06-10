package tdz.controller.io

import tdz.model.Canvas
import tdz.model.Card
import tdz.model.localstorage.CanvasDoc
import tdz.model.markdown.MarkdownService

/**
 * The `.tdz` file format: a single JSON line describing the canvas structure, a newline, then the
 * canvas's cards as markdown. This is the one place that knows the format — both [encode] (download)
 * and [decode] (upload) go through here so the two never drift apart.
 *
 * @param prototype the canvas structure parsed from the header line, or null for a header-less file
 *                  (plain markdown — still importable as loose cards).
 * @param cards     the cards parsed from the markdown body, flattened across columns.
 */
data class TdzDocument(val prototype: Canvas?, val cards: List<Card>) {

    companion object {

        fun encode(canvas: Canvas, cards: List<Card>): String {
            val header = CanvasDoc(canvas.name, canvas.parent, canvas.columns, canvas.layouts).encode()
            return header + "\n" + MarkdownService.toMd(cards)
        }

        fun decode(content: String): TdzDocument {
            val firstLine = content.substringBefore("\n")
            val (prototype, markdown) = parseHeader(firstLine)?.let { proto ->
                proto to content.removePrefix(firstLine)
            } ?: (null to content)
            val cards = MarkdownService.fromMd(markdown).flatten()
            return TdzDocument(prototype, cards)
        }

        /** Returns the canvas described by a header line, or null if the line is not a valid header. */
        private fun parseHeader(firstLine: String): Canvas? {
            if (!firstLine.startsWith("{")) return null
            return try {
                val doc = CanvasDoc.decode(firstLine)
                Canvas(doc.name, doc.parentName, doc.columns, doc.layouts)
            } catch (e: Throwable) {
                null  // unparseable header → treat the whole file as header-less markdown
            }
        }
    }
}
