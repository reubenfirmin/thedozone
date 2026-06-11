package tdz.model

import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.card.CardListItem
import tdz.model.card.ListGroup
import tdz.model.localstorage.*
import tdz.model.markdown.MarkdownService
import tdz.rootCanvas
import zoned.framework.Debugger.debug

/**
 * The app's persistent state: canvases, columns (markdown), cards (projections of that markdown),
 * and per-column metadata, all backed by localStorage via [LocalStorageModel]. Pure storage —
 * mutation ORCHESTRATION (events, ordering, sync notification) lives in [tdz.controller.Controller].
 */
class Model(val initialCanvasName: CanvasName): CanvasChecker, tdz.controller.io.CanvasStore {

    private val fs = LocalStorageModel()

    private var currentCanvas: Canvas? = null

    fun currentCanvas(): Canvas = currentCanvas ?: run {
        if (initialCanvasName == rootCanvas && !exists(initialCanvasName)) {
            // if we're creating from scratch, main is a canvas with one column
            addCanvas(canvasFromTemplate(rootCanvas, null, SINGLE_PANE))
        }
        val loaded = getCanvas(initialCanvasName)
            ?: error("Canvas '${initialCanvasName.normalized()}' is missing from storage (corrupt or deleted?)")
        currentCanvas = loaded
        loaded
    }

    /**
     * Recursive function that produces list of canvases from top down to this canvas, inclusive
     */
    fun path(canvasName: CanvasName): List<CanvasName> {

        if (canvasName.normalized().isEmpty() || canvasName == rootCanvas) {
            return listOf(rootCanvas)
        }
        val canvas = getCanvas(canvasName) ?: throw Exception("ERROR - null canvas when navigating for $canvasName")

        return if (canvas.parent == null) {
            listOf(canvasName)
        } else {
            return path(canvas.parent) + canvas.name
        }
    }

    fun changeCanvas(canvasName: CanvasName) {
        currentCanvas = getCanvas(canvasName)
    }

    override fun cards(canvasName: CanvasName, columnId: ColumnId): List<Card> {
        val stored = fs.getColumn(canvasName, columnId) ?: return listOf()
        return MarkdownService.fromMd(stored, columnId)
    }

    fun allCards(canvasName: CanvasName): List<List<Card>> {
        val canvas = getCanvas(canvasName)
            ?: error("Canvas '${canvasName.normalized()}' not found")
        return canvas.columns.map {
            cards(canvasName, it)
        }
    }

    fun getCardByIndex(canvasName: CanvasName, ref: CardRef): Card {
        val cards = cards(canvasName, ref.columnId)
        require(ref.index in cards.indices) {
            "No card at index ${ref.index} in column ${ref.columnId} of '${canvasName.normalized()}' " +
                "(${cards.size} cards) — stale element id?"
        }
        return cards[ref.index]
    }

    override fun getCanvas(canvasName: CanvasName): Canvas? = fs.getCanvas(canvasName)

    override fun addCanvas(canvas: Canvas) {
        fs.storeCanvas(canvas)
    }

    override fun saveCards(canvasName: CanvasName, column: ColumnId, cards: List<Card>) {
        // Preserve the column's style header (it isn't a card, so toMd doesn't emit it).
        val style = MarkdownService.readColumnStyle(fs.getColumn(canvasName, column) ?: "")
        val body = MarkdownService.toMd(cards.map {
            it.copy(metadata = it.metadata.copy(columnId = column))
        })
        fs.storeColumn(column, canvasName, MarkdownService.columnStyleHeader(style) + body)
    }

    fun updateColumn(canvasName: CanvasName, column: ColumnId, markdown: String) {
        fs.storeColumn(column, canvasName, markdown)
    }

    /** Moves [srcCard] into [toCanvas] (creating it if needed); returns the destination column. */
    fun moveCardToCanvas(srcCard: Card, fromCanvas: CanvasName, toCanvas: CanvasName): ColumnId {

        val canvasBeingMoved = srcCard.linkedCanvas()
        if (canvasBeingMoved != null) {
            // canvas is being moved - the source card represents a canvas, so we'll move that canvas (and the card)
            // to the dest
            if (canvasBeingMoved != toCanvas) {
                val existing = fs.getCanvas(canvasBeingMoved)
                if (existing != null) {
                    // The link card stands for its canvas: moving it moves the canvas in the
                    // hierarchy, so the linked canvas's parent follows the card.
                    fs.storeCanvas(Canvas(existing.name, toCanvas, existing.columns, existing.layouts))
                } // otherwise, it's a new canvas, and we don't need to store it yet
            } else {
                // happens when user drops card onto itself??
                debug("Card dropped onto itself, ignoring")
            }
        }

        val destCanvas = fs.getCanvas(toCanvas)
            ?: canvasFromTemplate(name = toCanvas, parent = fromCanvas, SINGLE_PANE).also { fs.storeCanvas(it) }

        val intendedColumn = srcCard.metadata.columnId

        val destColumn = if (destCanvas.columns.contains(intendedColumn)
            && !destCanvas.layoutMap.containsKey(intendedColumn)) {
            intendedColumn
        } else {
            destCanvas.firstNonLayout()
        }

        // update cards
        val cards = cards(toCanvas, destColumn)
        saveCards(toCanvas, destColumn, cards + srcCard)
        // Removal is positional by design (cards have no stable ids; markdown is the source of truth).
        remove(fromCanvas, srcCard.metadata.columnId, srcCard.index)
        return destColumn
    }

    fun deleteColumn(canvasName: CanvasName, columnId: ColumnId) {
        // NOTE does not change canvas metadata
        fs.deleteColumn(canvasName, columnId)
    }

    fun toggleDone(canvasName: CanvasName, card: Card, taskStep: CardListItem) {
        // List items live inside a ListGroup (card.segments holds the group, not the item),
        // so recurse into the group(s) to flip the matching item's done flag.
        val updated = card.segments.map { segment ->
            if (segment is ListGroup) toggleInGroup(segment, taskStep) else segment
        }
        replace(canvasName, card, card.copy(segments = updated))
    }

    private fun toggleInGroup(group: ListGroup, target: CardListItem): ListGroup =
        group.copy(members = group.members.map { member ->
            when (member) {
                is CardListItem -> if (member == target) member.copy(done = !member.done) else member
                is ListGroup -> toggleInGroup(member, target)
                else -> member
            }
        })

    fun clearDone(canvasName: CanvasName): List<ColumnId> {
        val changedCols = mutableSetOf<ColumnId>()
        allCards(canvasName).flatten().forEach { card ->
            val cleared = card.withoutDoneItems()
            if (cleared != card) {
                replace(canvasName, card, cleared)
                changedCols.add(card.metadata.columnId)
            }
        }
        return changedCols.toList()
    }

    fun size(canvasName: CanvasName, column: ColumnId) = cards(canvasName, column).size

    private fun replace(canvasName: CanvasName, card: Card, newCard: Card, newIdx: Int? = null) {
        remove(canvasName, card.metadata.columnId, card.index)
        if (newIdx != null) {
            add(canvasName, newCard.metadata.columnId, newCard, newIdx)
        } else {
            add(canvasName, newCard.metadata.columnId, newCard, card.index)
        }
    }

    private fun add(canvasName: CanvasName, column: ColumnId, card: Card, index: Int) {
        val cards = cards(canvasName, column)
        val newCards = cards.toMutableList()
        if (index < cards.size) {
            newCards.add(index, card)
        } else {
            newCards.add(card)
        }
        saveCards(canvasName, column, newCards)
    }

    private fun remove(canvasName: CanvasName, column: ColumnId, cardIdx: Int) {
        val cards = cards(canvasName, column)
        val newCards = cards.toMutableList()
        newCards.removeAt(cardIdx)
        saveCards(canvasName, column, newCards)
    }

    override fun exists(canvasName: CanvasName) = fs.getCanvas(canvasName) != null

    fun getColumnMode(canvasName: CanvasName, columnId: ColumnId): Mode? {
        return fs.getColumnMode(canvasName, columnId)
    }

    fun setColumnMode(canvasName: CanvasName, columnId: ColumnId, mode: Mode) {
        fs.storeColumnMode(canvasName, columnId, mode)
    }

    /**
     * Per-column visual overrides (wallpaper image url + background color); blanks = no override.
     * Lives in the column's own markdown (a header line) so it syncs with column content.
     */
    fun getColumnStyle(canvasName: CanvasName, columnId: ColumnId): ColumnStyle =
        MarkdownService.readColumnStyle(fs.getColumn(canvasName, columnId) ?: "")

    /** Rewrites the column file's style header in place, preserving the card body. */
    fun setColumnStyle(canvasName: CanvasName, columnId: ColumnId, style: ColumnStyle) {
        val existing = fs.getColumn(canvasName, columnId) ?: ""
        val markdown = MarkdownService.columnStyleHeader(style) + MarkdownService.stripColumnStyle(existing)
        fs.storeColumn(columnId, canvasName, markdown)
    }

    /** Persisted UI theme name (global), or null if never set. */
    fun getTheme(): String? = fs.getTheme()

    fun setTheme(name: String) = fs.storeTheme(name)

    /** Raw stored markdown for a column (null if absent) — used by sync to push exact bytes. */
    fun getCanvasColumnRaw(canvasName: CanvasName, columnId: ColumnId): String? =
        fs.getColumn(canvasName, columnId)

    fun readSyncConfig() = fs.readSyncConfig()
    fun writeSyncConfig(doc: tdz.model.localstorage.SyncConfigDoc) = fs.writeSyncConfig(doc)
    fun readSyncState() = fs.readSyncState()
    fun writeSyncState(doc: tdz.model.localstorage.SyncStateDoc) = fs.writeSyncState(doc)
}
