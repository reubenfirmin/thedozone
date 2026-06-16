package tdz.controller

import tdz.ancestry
import tdz.rootCanvas
import tdz.controller.io.CanvasStore
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.CardMetadata
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.card.CanvasLink
import tdz.model.card.CardText
import tdz.model.card.RichText

/**
 * Board-hierarchy business logic: turning a path into the chain of boards it names. A domain
 * service (like [tdz.controller.io.CanvasImporter] / [ColumnSplit]) that operates on a narrow
 * [CanvasStore], so it is unit-testable without localStorage and called — not contained — by the
 * orchestration layer.
 */
object CanvasHierarchy {

    /**
     * Create every missing board along [name]'s path (each parented to its path-parent) and seed
     * each NEW board with a `[..]` parent link plus a `[<child>]` link in its parent (top-level
     * boards link into root). Idempotent — only the creation branch appends, so revisiting a path
     * adds nothing. Root anchors the hierarchy and is materialized if absent.
     */
    fun ensurePath(store: CanvasStore, name: CanvasName) {
        if (!store.exists(rootCanvas)) store.addCanvas(canvasFromTemplate(rootCanvas, null, SINGLE_PANE))
        ancestry(name).forEach { board ->
            if (!store.exists(board)) {
                val parent = board.parentName()
                store.addCanvas(canvasFromTemplate(board, parent, SINGLE_PANE))
                appendLinkCard(store, board, "..")            // parent link in the new board
                appendLinkCard(store, parent, board.leaf())   // child link in the parent (root or ancestor)
            }
        }
    }

    /** Append a canvas-link card to [canvas]'s first content column, built through the card model
     *  (NOT raw markdown) so link serialization stays in one place ([CanvasLink.toMarkdown]). */
    private fun appendLinkCard(store: CanvasStore, canvas: CanvasName, target: String) {
        val c = store.getCanvas(canvas) ?: return
        val col = c.firstNonLayout()
        val existing = store.cards(canvas, col)
        val card = Card(listOf(CardText(listOf<RichText>(CanvasLink(target, target)))), CardMetadata(col), existing.size)
        store.saveCards(canvas, col, existing + card)
    }
}
