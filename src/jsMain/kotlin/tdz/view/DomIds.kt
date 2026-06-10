package tdz.view

import tdz.model.CanvasName
import tdz.model.CardRef
import tdz.model.ColumnId

/**
 * The single encode/decode point for the canvas's dynamic element ids (per-column, per-card,
 * per-canvas-link — the static body-level ids live in [Zones]). Builders and parsers live together
 * so a format change can never silently break drag-and-drop, which decodes these ids from
 * Sortable's events.
 */
object DomIds {

    private const val COLUMN = "column-"
    private const val CARD = "card-"
    private const val CANVAS_LINK = "canvas-link-"
    private const val EDITOR = "editor-"

    /** CSS attribute selector matching card elements (Sortable's draggable filter, parked-card sweeps). */
    const val CARD_SELECTOR = "[id^='$CARD']"

    fun column(columnId: ColumnId) = "$COLUMN$columnId"

    /** The columnId encoded in a column element id, or null if [elementId] isn't one. */
    fun columnId(elementId: String): ColumnId? =
        elementId.takeIf { it.startsWith(COLUMN) }?.removePrefix(COLUMN)?.toIntOrNull()?.let(::ColumnId)

    /** Positional card id: the card's column and its index within it (pre-render order). */
    fun card(ref: CardRef) = "$CARD${ref.columnId}-${ref.index}"

    /** The [CardRef] encoded in a card element id, or null if [elementId] isn't one. */
    fun cardId(elementId: String): CardRef? {
        if (!isCard(elementId)) return null
        val parts = elementId.removePrefix(CARD).split("-")
        if (parts.size != 2) return null
        val column = parts[0].toIntOrNull() ?: return null
        val index = parts[1].toIntOrNull() ?: return null
        return CardRef(ColumnId(column), index)
    }

    fun isCard(elementId: String) = elementId.startsWith(CARD)

    fun canvasLink(name: CanvasName) = "$CANVAS_LINK${name.normalized()}"

    /** The canvas a canvas-link drop zone points at, or null if [elementId] isn't one. */
    fun canvasLinkName(elementId: String): CanvasName? =
        elementId.takeIf { it.startsWith(CANVAS_LINK) }?.removePrefix(CANVAS_LINK)?.let { CanvasName(it) }

    fun editor(columnId: ColumnId) = "$EDITOR$columnId"
}
