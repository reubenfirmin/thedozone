package tdz.controller

import tdz.controller.events.AddColumn
import tdz.controller.events.CardsReordered
import tdz.controller.events.CardsSentToCanvas
import tdz.controller.events.ClearDone
import tdz.controller.events.ColumnEdited
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.DeleteColumn
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.RefreshCard
import tdz.controller.events.RefreshColumns
import tdz.model.ref
import tdz.controller.events.SplitColumn
import tdz.controller.events.TaskToggleDone
import tdz.model.Model
import tdz.model.markdown.MarkdownService

class Controller(eventBus: EventBus<Event>, model: Model) {

    init {
        eventBus.on<TaskToggleDone> { event ->
            model.toggleDone(event.canvasName, event.card, event.ordinal)
            val changed = setOf(event.card.metadata.columnId)
            eventBus.fire(ColumnsChanged(event.canvasName, changed))
            // Toggling rewrites the card's content but not the column's order, so the card's ref is
            // still valid — re-render just that card in place instead of rebuilding the column.
            eventBus.fire(RefreshCard(event.canvasName, event.card.ref))
        }

        eventBus.on<ColumnEdited> { event ->
            // The editor rebuilds its text from cards and omits the column's style header; preserve
            // the existing header so editing cards doesn't drop the column's wallpaper/color.
            val style = model.getColumnStyle(event.canvasName, event.columnId)
            model.updateColumn(event.canvasName, event.columnId,
                MarkdownService.columnStyleHeader(style) +
                    MarkdownService.stripTrailingEmptyListItems(MarkdownService.stripColumnStyle(event.markdown)))
            eventBus.fire(ColumnsChanged(event.canvasName, setOf(event.columnId)))
            if (event.refresh) {
                eventBus.fire(RefreshColumns(event.canvasName, setOf(event.columnId)))
            }
        }

        eventBus.on<ClearDone> { event ->
            val changed = model.clearDone(event.canvasName)
            if (changed.isNotEmpty()) {
                eventBus.fire(ColumnsChanged(event.canvasName, changed.toSet()))
                eventBus.fire(RefreshColumns(event.canvasName, changed.toSet()))
            }
        }

        eventBus.on<CardsReordered> { event ->
            model.saveCards(event.canvasName, event.toColumnId, event.toCards)
            event.fromCards?.let { model.saveCards(event.canvasName, event.fromColumnId, it) }
            val changed = setOf(event.fromColumnId, event.toColumnId)
            eventBus.fire(ColumnsChanged(event.canvasName, changed))
            // Re-render so the positional card ids reflect the new order — except when the drop
            // already left the DOM in the persisted order with renumbered ids (single-card drags):
            // rebuilding then would only re-create, element by element, what the browser is showing.
            if (!event.domAlreadyOrdered) {
                eventBus.fire(RefreshColumns(event.canvasName, changed))
            }
        }

        eventBus.on<CardsSentToCanvas> { event ->
            // Highest source index first so each removal doesn't shift the cards not yet moved.
            // Indices are column-local, but a single global descending sort is still safe for a
            // multi-column group: within any one column the sorted subsequence is also descending,
            // and removals never affect another column's indices.
            val targetColumns = event.cards.sortedByDescending { it.index }
                .map { model.moveCardToCanvas(it, event.sourceCanvas, event.targetCanvas) }
                .toSet()
            val sourceColumns = event.cards.map { it.metadata.columnId }.toSet()
            eventBus.fire(ColumnsChanged(event.sourceCanvas, sourceColumns))
            eventBus.fire(ColumnsChanged(event.targetCanvas, targetColumns))
            eventBus.fire(RefreshColumns(event.sourceCanvas, sourceColumns))
            // The user stays on the source canvas, but the target's manifest may be new/changed —
            // structural so sync pushes it (Index skips re-rendering a non-current canvas).
            eventBus.fire(RefreshCanvas(event.targetCanvas, structural = true))
        }

        eventBus.on<SplitColumn> { event ->
            applyLayout(model, eventBus, event.canvasName) { canvas ->
                ColumnSplit.splitColumn(canvas, event.columnId, event.split)
            }
        }

        eventBus.on<AddColumn> { event ->
            applyLayout(model, eventBus, event.canvasName) { canvas ->
                ColumnSplit.addColumn(canvas, event.columnId, event.split)
            }
        }

        eventBus.on<DeleteColumn> { event ->
            model.getCanvas(event.canvasName)?.let { canvas ->
                // A corrupt/foreign layout tree must never crash mid-delete (a half-applied delete
                // is how a board loses its last columns) — refuse on throw.
                val updated = runCatching { ColumnSplit.deleteColumn(canvas, event.columnId).first }
                    .getOrElse { t ->
                        console.error("[delete-column] refused, layout tree not deletable: ${t.message}")
                        return@let
                    }
                // FUNDAMENTAL INVARIANT: a canvas always keeps at least one CONTENT column. Apply the
                // delete only if its result still has one; otherwise this was the last content column
                // (or ColumnSplit produced an empty/corrupt result) — EMPTY the target instead of
                // removing it. Emptying (not deleting the file) also avoids the sync re-import: a
                // null file gets pulled back from the remote; an empty file is a model fact that
                // converges. This guard does not trust ColumnSplit's own count — it checks the result.
                val contentAfter = updated.columns.filterNot { updated.layoutMap.containsKey(it) }
                if (updated != canvas && contentAfter.isNotEmpty()) {
                    model.deleteColumn(event.canvasName, event.columnId)   // discard the column's cards
                    model.addCanvas(updated)        // persist rearranged layout (overwrites)
                } else {
                    model.updateColumn(event.canvasName, event.columnId, "")
                    eventBus.fire(ColumnsChanged(event.canvasName, setOf(event.columnId)))
                }
                model.changeCanvas(event.canvasName)
                eventBus.fire(RefreshCanvas(event.canvasName))
            }
        }
    }

    /** Apply a layout transform to a canvas, persist it (overwrites), and re-render. */
    private fun applyLayout(
        model: Model,
        eventBus: EventBus<Event>,
        canvasName: tdz.model.CanvasName,
        transform: (tdz.model.Canvas) -> tdz.model.Canvas,
    ) {
        val canvas = model.getCanvas(canvasName) ?: return
        val updated = transform(canvas)
        model.addCanvas(updated)        // fs.storeCanvas overwrites by name
        model.changeCanvas(canvasName)  // reload currentCanvas from the updated spec
        eventBus.fire(RefreshCanvas(canvasName))
    }
}