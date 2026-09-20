package tdz.controller.io

import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.CardMetadata
import tdz.model.ColumnId
import tdz.model.card.CanvasLink
import tdz.model.card.CardText
import tdz.model.card.RichText

/**
 * Append a canvas-link card to [canvas]'s first content column, built through the card model (NOT
 * raw markdown) so link serialization stays in one place ([CanvasLink.toMarkdown]). The single
 * builder shared by [tdz.controller.CanvasHierarchy] (relative `..`/child links) and
 * [CanvasImporter] (absolute `/path` links); callers supply the already-resolved [target] address
 * and its [display] text. Returns the column the link landed in (or null if the canvas is absent).
 */
fun CanvasStore.appendLinkCard(canvas: CanvasName, target: String, display: String = target): ColumnId? {
    val c = getCanvas(canvas) ?: return null
    val col = c.firstNonLayout()
    val existing = cards(canvas, col)
    val card = Card(listOf(CardText(listOf<RichText>(CanvasLink(target, display)))), CardMetadata(col), existing.size)
    saveCards(canvas, col, existing + card)
    return col
}
