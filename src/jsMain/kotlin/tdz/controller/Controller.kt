package tdz.controller

import tdz.controller.events.AddColumn
import tdz.controller.events.CardsReordered
import tdz.controller.events.CardsSentToCanvas
import tdz.controller.events.ClearDone
import tdz.controller.events.ColumnEdited
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.DeleteColumn
import tdz.controller.events.Event
import tdz.controller.events.ExportCanvas
import tdz.controller.events.ImportCanvasAsNew
import tdz.controller.events.ImportCardsHere
import tdz.controller.io.Downloader
import tdz.controller.io.ImportMode
import tdz.controller.io.Uploader
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.RefreshCard
import tdz.controller.events.RefreshColumns
import tdz.model.ref
import tdz.controller.events.SplitColumn
import tdz.controller.events.TaskToggleDone
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.Model
import tdz.model.markdown.MarkdownService

class Controller(private val eventBus: EventBus<Event>, private val model: Model) {

    // The view never touches file I/O directly; it fires ExportCanvas / ImportCanvasAsNew /
    // ImportCardsHere and the Controller owns these model<->file collaborators (PNG capture is a
    // view concern, kept there). ImportMode is a controller.io detail — the intent events carry no
    // payload, and this is the only place that maps intent -> mode.
    private val downloader = Downloader(model)
    private val uploader = Uploader(model, eventBus)

    /**
     * Open a board by path: dispatch to [CanvasHierarchy] to build any missing chain (the hierarchy
     * business logic lives there), make the leaf current, and return it for the view to render. This
     * is dispatch only — the route calls it; the Controller does not contain the logic.
     */
    fun openCanvasPath(name: CanvasName): Canvas {
        if (!name.isRoot()) CanvasHierarchy.ensurePath(model, name)
        model.changeCanvas(name)
        return model.currentCanvas()
    }

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

        eventBus.on<ExportCanvas> { event ->
            model.getCanvas(event.canvasName)?.let { downloader.download(it) }
        }

        eventBus.on<ImportCanvasAsNew> { uploader.upload(ImportMode.NEW_CANVAS) }
        eventBus.on<ImportCardsHere> { uploader.upload(ImportMode.HERE) }

        eventBus.on<CardsReordered> { event ->
            // Persist the model fact and stamp the columns for sync. Re-rendering is the producer's
            // call (DragDrop fires RefreshColumns only when the drop left the DOM stale), so this
            // handler never rebuilds — a single-card drop already matches the live DOM.
            model.saveCards(event.canvasName, event.toColumnId, event.toCards)
            event.fromCards?.let { model.saveCards(event.canvasName, event.fromColumnId, it) }
            eventBus.fire(ColumnsChanged(event.canvasName, setOf(event.fromColumnId, event.toColumnId)))
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
            applyLayout(event.canvasName) { canvas ->
                ColumnSplit.splitColumn(canvas, event.columnId, event.split)
            }
        }

        eventBus.on<AddColumn> { event ->
            applyLayout(event.canvasName) { canvas ->
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
                val contentAfter = updated.leafColumns()
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
        canvasName: CanvasName,
        transform: (Canvas) -> Canvas,
    ) {
        // Loud guards: a layout action that quietly does nothing (no canvas, a throwing transform,
        // or a no-op result) is exactly the "click the icon, nothing happens, no error" failure —
        // surface each so it can never fail silently again.
        val canvas = model.getCanvas(canvasName) ?: run {
            console.error("[layout] no stored canvas '${canvasName.normalized()}' — current board is " +
                "stale (its manifest isn't in storage); cannot apply the layout change.")
            return
        }
        val updated = runCatching { transform(canvas) }.getOrElse { t ->
            console.error("[layout] transform of '${canvasName.normalized()}' failed: ${t.message} " +
                "(columns=${canvas.columns}, layouts=${canvas.layouts})")
            return
        }
        if (updated == canvas) {
            console.warn("[layout] transform of '${canvasName.normalized()}' was a no-op " +
                "(columns=${canvas.columns}, layouts=${canvas.layouts}).")
            return
        }
        model.addCanvas(updated)        // fs.storeCanvas overwrites by name
        model.changeCanvas(canvasName)  // reload currentCanvas from the updated spec
        eventBus.fire(RefreshCanvas(canvasName))
    }
}