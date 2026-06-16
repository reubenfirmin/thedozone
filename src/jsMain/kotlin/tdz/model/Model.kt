package tdz.model

import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.card.CardListItem
import tdz.model.card.ListGroup
import tdz.model.localstorage.*
import tdz.model.markdown.MarkdownService
import tdz.rootCanvas


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

    fun changeCanvas(canvasName: CanvasName) {
        // Root is the empty path and is never created by ensurePath, yet it must always be
        // reachable (e.g. PageUp to root from a top-level board after a cold deep-link, where root
        // was never materialized). Materialize it on demand so navigation never lands on null.
        if (canvasName.isRoot() && !exists(canvasName)) {
            addCanvas(canvasFromTemplate(rootCanvas, null, SINGLE_PANE))
        }
        currentCanvas = getCanvas(canvasName)
    }

    /**
     * Parse cache, keyed by raw-markdown equality rather than write-path invalidation: every read
     * still fetches the raw string (cheap), and re-parses only when it differs from the cached
     * raw. So a write from ANY path — editor save, drag persist, a sync pull writing localStorage
     * underneath this instance — naturally misses the cache; there is no hook to forget.
     * Without this, cards() re-parsed on every call and getCardByIndex made drops O(n²).
     */
    private val parsedColumns = mutableMapOf<String, Pair<String, List<Card>>>()

    override fun cards(canvasName: CanvasName, columnId: ColumnId): List<Card> {
        val stored = fs.getColumn(canvasName, columnId) ?: return listOf()
        val key = "${canvasName.normalized()}|$columnId"
        parsedColumns[key]?.let { (raw, parsed) -> if (raw == stored) return parsed }
        val parsed = MarkdownService.fromMd(stored, columnId)
        parsedColumns[key] = stored to parsed
        return parsed
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

    /** Moves [srcCard] into [toCanvas] (creating it if needed); returns the destination column.
     *  Reparenting via drag is intentionally removed — a board's parent is set only at creation.
     *  (Explicit reparent will come later via a context-menu action.) */
    fun moveCardToCanvas(srcCard: Card, fromCanvas: CanvasName, toCanvas: CanvasName): ColumnId {
        val destCanvas = fs.getCanvas(toCanvas)
            ?: canvasFromTemplate(name = toCanvas, parent = toCanvas.parentName(), SINGLE_PANE).also { fs.storeCanvas(it) }

        val intendedColumn = srcCard.metadata.columnId
        val destColumn = if (destCanvas.columns.contains(intendedColumn)
            && !destCanvas.layoutMap.containsKey(intendedColumn)) {
            intendedColumn
        } else {
            destCanvas.firstNonLayout()
        }

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

    /** Remove our orphaned keys (mode flags + legacy config); returns (purged, orphanContentKeys). */
    fun purgeOrphans(): Pair<Int, List<String>> = fs.purgeOrphans()

    /** Every canvas with a manifest in storage — the "all boards" list source. */
    fun allCanvasNames(): List<CanvasName> = fs.allCanvasNames()

    /** True when a board has no content columns, or every content column is empty. */
    fun isCanvasEmpty(canvasName: CanvasName): Boolean {
        val canvas = getCanvas(canvasName) ?: return true
        val content = canvas.columns.filterNot { canvas.layoutMap.containsKey(it) }
        return content.all { cards(canvasName, it).isEmpty() }
    }

    /**
     * Fully delete a board LOCALLY: its manifest, every column's content, and column-mode flags.
     * Offline-first and sync-agnostic — it touches NO remote and NO sync bookkeeping. Sync, as an
     * independent listener, propagates the deletion to the remote on its next reconcile (its
     * orphan-sweep sees the columns gone from local state). Caller handles backup + re-render.
     */
    fun deleteCanvas(canvasName: CanvasName) {
        getCanvas(canvasName)?.columns?.forEach { col -> fs.deleteColumn(canvasName, col) }
        fs.deleteCanvas(canvasName)
    }

    /**
     * Flip the [ordinal]-th checkable item of [card] — the position in [Card.Companion.taskItems]
     * (render order), counted depth-first through the nested [ListGroup]s. Identity is POSITIONAL,
     * never structural: list items are data classes, so two tasks with identical text are == and
     * an equality match would flip every twin at once.
     */
    fun toggleDone(canvasName: CanvasName, card: Card, ordinal: Int) {
        var seen = 0
        fun toggleInGroup(group: ListGroup): ListGroup =
            group.copy(members = group.members.map { member ->
                when (member) {
                    is CardListItem -> if (seen++ == ordinal) member.copy(done = !member.done) else member
                    is ListGroup -> toggleInGroup(member)
                    else -> member
                }
            })
        // List items live inside a ListGroup (card.segments holds the group, not the item),
        // so recurse into the group(s) to flip the target item's done flag.
        val updated = card.segments.map { segment ->
            if (segment is ListGroup) toggleInGroup(segment) else segment
        }
        replace(canvasName, card, card.copy(segments = updated))
    }

    fun clearDone(canvasName: CanvasName): List<ColumnId> {
        val canvas = getCanvas(canvasName)
            ?: error("Canvas '${canvasName.normalized()}' not found")
        val changedCols = mutableListOf<ColumnId>()
        for (col in canvas.columns) {
            // ONE atomic write per column — never per-card replace()s against a snapshot. A card
            // that was nothing but done items clears to empty and vanishes on the serialize→parse
            // round trip, so every later snapshot index would point one card too far right: each
            // later replace then destroyed an unrelated card (its unchecked items included).
            val before = cards(canvasName, col)
            val cleared = before.map { it.withoutDoneItems() }
            if (cleared != before) {
                // Drop fully-cleared cards explicitly rather than serializing empty husks.
                saveCards(canvasName, col, cleared.filterNot { it.segments.isEmpty() })
                changedCols.add(col)
            }
        }
        return changedCols
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
