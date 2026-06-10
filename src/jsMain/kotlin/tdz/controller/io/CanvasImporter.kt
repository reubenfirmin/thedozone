package tdz.controller.io

import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.CardMetadata
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Split
import tdz.model.card.CanvasLink
import tdz.model.card.CardText
import tdz.model.card.RichText

/** Where to put the cards from an uploaded [TdzDocument]. */
enum class ImportMode {
    /** Create a brand-new canvas and add a link to it on the current canvas. */
    NEW_CANVAS,

    /** Merge the cards into the current canvas. */
    HERE,
}

/** What changed, so the caller can refresh the right canvas. */
data class ImportResult(val canvas: CanvasName, val changedColumns: List<ColumnId>)

/**
 * The persistence surface [CanvasImporter] needs. [tdz.model.Model] already satisfies it; tests
 * supply an in-memory fake. Keeping the dependency narrow keeps the import logic unit-testable
 * without touching localStorage.
 */
interface CanvasStore {
    fun exists(canvasName: CanvasName): Boolean
    fun getCanvas(canvasName: CanvasName): Canvas?
    fun addCanvas(canvas: Canvas)
    fun cards(canvasName: CanvasName, columnId: ColumnId): List<Card>
    fun saveCards(canvasName: CanvasName, column: ColumnId, cards: List<Card>)
}

/**
 * Imports a parsed [TdzDocument] into the model. Pure of any DOM/file concerns — those live in the
 * caller (FilePicker/Uploader) — so the placement logic can be tested directly.
 */
class CanvasImporter(private val store: CanvasStore) {

    fun import(fileName: String, doc: TdzDocument, mode: ImportMode, currentCanvas: Canvas): ImportResult =
        when (mode) {
            ImportMode.NEW_CANVAS -> importAsNewCanvas(fileName, doc, currentCanvas)
            ImportMode.HERE -> importHere(doc, currentCanvas)
        }

    private fun importAsNewCanvas(fileName: String, doc: TdzDocument, currentCanvas: Canvas): ImportResult {
        val name = uniqueName(fileName)
        if (doc.prototype != null) {
            store.addCanvas(Canvas(name, currentCanvas.name, doc.prototype.columns, doc.prototype.layouts))
        } else {
            val columnCount = doc.cards.map { it.metadata.columnId }.distinct().size
            store.addCanvas(
                Canvas(
                    name, currentCanvas.name, (0..columnCount).toList(),
                    listOf(ColumnLayoutSpec(0, Split.VERTICAL, (1..columnCount).toList())),
                )
            )
        }
        saveCardsInto(name, doc.cards)
        val linkColumn = addLinkCard(currentCanvas, name)
        return ImportResult(currentCanvas.name, listOf(linkColumn))
    }

    private fun importHere(doc: TdzDocument, currentCanvas: Canvas): ImportResult {
        val changed = saveCardsInto(currentCanvas.name, doc.cards)
        return ImportResult(currentCanvas.name, changed)
    }

    /** Save cards grouped by their source column, remapping any non-leaf target onto the first leaf. */
    private fun saveCardsInto(canvasName: CanvasName, cards: List<Card>): List<ColumnId> {
        val canvas = store.getCanvas(canvasName)
            ?: error("Import target canvas '${canvasName.normalized()}' not found")
        val leaves = canvas.columns.filterNot { canvas.layoutMap.containsKey(it) }.toSet()
        val changed = mutableListOf<ColumnId>()
        for ((columnId, group) in cards.groupBy { it.metadata.columnId }) {
            val target = if (columnId in leaves) columnId else canvas.firstNonLayout()
            store.saveCards(canvasName, target, store.cards(canvasName, target) + group)
            changed.add(target)
        }
        return changed.distinct()
    }

    private fun addLinkCard(currentCanvas: Canvas, target: CanvasName): ColumnId {
        val column = currentCanvas.firstNonLayout()
        val existing = store.cards(currentCanvas.name, column)
        val link = Card(
            listOf(CardText(listOf<RichText>(CanvasLink(target, target.display())))),
            CardMetadata(column),
            existing.size,
        )
        store.saveCards(currentCanvas.name, column, existing + link)
        return column
    }

    /** Strip path-breaking characters and append '_' until the name is free. */
    private fun uniqueName(fileName: String): CanvasName {
        var name = CanvasName(fileName).normalized()
        while (store.exists(CanvasName(name))) {
            name = CanvasName("${name}_").normalized()
        }
        return CanvasName(name)
    }
}
