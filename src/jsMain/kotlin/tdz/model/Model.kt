package tdz.model

import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.card.CardListItem
import tdz.model.card.ListGroup
import tdz.model.localstorage.*
import tdz.model.markdown.MarkdownService
import tdz.rootCanvas
import zoned.framework.Debugger.debug

val version = VersionDoc(0, 8, 0)

/**
 * Model must be created first so that it can register its event hooks at the top
 */
class Model(val initialCanvasName: CanvasName): CanvasChecker, tdz.controller.io.CanvasStore {

    private val fs = LocalStorageModel()

    private var currentCanvas: Canvas? = null

    fun currentCanvas(): Canvas = if (currentCanvas == null) {
        val currentCanvasName = initialCanvasName
        if (currentCanvasName == rootCanvas && !exists(currentCanvasName)) {
            // if we're creating from scratch, main is a canvas with one column
            addCanvas(canvasFromTemplate(rootCanvas, null, SINGLE_PANE))
        }
        currentCanvas = getCanvas(currentCanvasName)
        currentCanvas!!
    } else {
        currentCanvas!!
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
        val stored = fs.getColumn(canvasName, columnId)
        console.log("Model.cards() called for canvas=$canvasName, columnId=$columnId, stored=${if (stored != null) "has ${stored.length} chars" else "null"}")
        return if (stored != null) {
            val cards = MarkdownService.fromMd(stored, columnId)
            console.log("Model.cards() returning ${cards.size} cards")
            cards
        } else {
            console.log("Model.cards() returning empty list (stored was null)")
            listOf()
        }
    }

    fun allCards(canvasName: CanvasName): List<List<Card>> {
        return getCanvas(canvasName)!!.columns.map {
            cards(canvasName, it)
        }
    }

    /**
     * @return true if "someVersion" is less than current version
     */
    fun greaterThan(observed: VersionDoc): Boolean {
        return (observed.major < version.major)
            || (observed.major == version.major && observed.minor < version.minor)
            || (observed.major == version.major && observed.minor == version.minor && observed.patch < version.patch)
    }

    fun readVersion() = fs.readVersion()

    fun writeVersion() = fs.writeVersion(version)

    fun getCardByIndex(canvasName: CanvasName, column: Int, idx: Int): Card {
        val cards = cards(canvasName, column)
        return cards[idx]
    }

    override fun getCanvas(canvasName: CanvasName): Canvas? = fs.getCanvas(canvasName)

    override fun addCanvas(canvas: Canvas) {
        fs.storeCanvas(canvas)
    }

    fun addCanvas(canvasName: CanvasName, parentName: CanvasName?,
                  columns: List<ColumnId>, layouts: List<ColumnLayoutSpec>) {
        fs.storeCanvas(Canvas(canvasName, parentName, columns, layouts))
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

    fun moveCardToCanvas(srcCard: Card, fromCanvas: CanvasName, toCanvas: CanvasName) {

        val canvasBeingMoved = srcCard.linkedCanvas()
        if (canvasBeingMoved != null) {
            // canvas is being moved - the source card represents a canvas, so we'll move that canvas (and the card)
            // to the dest
            if (canvasBeingMoved != toCanvas) {
                val existing = fs.getCanvas(canvasBeingMoved)
                if (existing != null) {
                    fs.storeCanvas(Canvas(existing.name, existing.parent, existing.columns, existing.layouts))
                } // otherwise, it's a new canvas, and we don't need to store it yet
            } else {
                // happens when user drops card onto itself??
                debug("Card dropped onto itself, ignoring")
            }
        }

        val destCanvas = fs.getCanvas(toCanvas) ?: run {
            // TODO change this to just return in single operation
            fs.storeCanvas(canvasFromTemplate(name = toCanvas, parent = fromCanvas, SINGLE_PANE))
            fs.getCanvas(toCanvas)!!
        }

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
        // TODO it sucks to remove a card by index
        remove(fromCanvas, srcCard.metadata.columnId, srcCard.index)
    }

    /**
     * @param beforeCard if null, add to end
     */
    fun moveCardToColumn(canvasName: CanvasName,
                         movedCard: Card,
                         beforeCard: Card? = null,
                         destColumn: Int) {

        if (beforeCard == null) { // goes at end
            val size = size(canvasName, destColumn)
            moveCardToColumn(canvasName, movedCard, size, destColumn)
        } else {
            moveCardToColumn(canvasName, movedCard, beforeCard.index, destColumn)
        }
    }

    fun moveCardToColumn(canvasName: CanvasName,
                         movedCard: Card,
                         toIndex: Int,
                         destColumn: Int) {
        replace(canvasName, movedCard, movedCard.copy(metadata = CardMetadata(destColumn)), toIndex)
    }

    fun delete(canvasName: CanvasName, column: Int, card: Card) {
        remove(canvasName, column, card.index)
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

    fun deleteCanvas(canvasName: CanvasName) {
        getCanvas(canvasName)?.let { canvas ->
            canvas.columns.forEach {
                fs.deleteColumn(canvasName, it)
            }
            fs.deleteCanvas(canvasName)
        }
    }

    fun clearDone(canvasName: CanvasName): List<Int> {
        val changedCols = mutableSetOf<Int>()
        allCards(canvasName).flatten().forEach { card ->
            val cleared = card.withoutDoneItems()
            if (cleared != card) {
                replace(canvasName, card, cleared)
                changedCols.add(card.metadata.columnId)
            }
        }
        return changedCols.toList()
    }

    fun size(canvasName: CanvasName, column: Int) = cards(canvasName, column).size

    private fun replace(canvasName: CanvasName, card: Card, newCard: Card, newIdx: Int? = null) {
        remove(canvasName, card.metadata.columnId, card.index)
        if (newIdx != null) {
            add(canvasName, newCard.metadata.columnId, newCard, newIdx)
        } else {
            add(canvasName, newCard.metadata.columnId, newCard, card.index)
        }
    }

    private fun add(canvasName: CanvasName, column: Int, card: Card, index: Int) {
        val cards = cards(canvasName, column)
        val newCards = cards.toMutableList()
        if (index < cards.size) {
            newCards.add(index, card)
        } else {
            newCards.add(card)
        }
        saveCards(canvasName, column, newCards)
    }

    private fun remove(canvasName: CanvasName, column: Int, cardIdx: Int) {
        val cards = cards(canvasName, column)
        val newCards = cards.toMutableList()
        newCards.removeAt(cardIdx)
        saveCards(canvasName, column, newCards)
    }

    override fun exists(canvasName: CanvasName) = fs.getCanvas(canvasName) != null

    fun getColumnMode(canvasName: CanvasName, columnId: ColumnId): String? {
        return fs.getColumnMode(canvasName, columnId)
    }

    fun setColumnMode(canvasName: CanvasName, columnId: ColumnId, mode: String) {
        fs.storeColumnMode(canvasName, columnId, mode)
    }

    /**
     * Per-column visual overrides (wallpaper image url + background colour); blanks = no override.
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
