package tdz.controller

import tdz.controller.events.AddColumn
import tdz.controller.events.ClearDone
import tdz.controller.events.ColumnEdited
import tdz.controller.events.DeleteColumn
import tdz.controller.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.RefreshColumns
import tdz.controller.events.SplitColumn
import tdz.controller.events.TaskToggleDone
import tdz.model.Model
import tdz.model.markdown.MarkdownService

class Controller(eventBus: EventBus, model: Model) {

    init {
        eventBus.on<TaskToggleDone> { event ->
            model.toggleDone(event.canvasName, event.card, event.segment)
            // Re-render the card's column from the (now updated) model so the checkbox reflects state.
            eventBus.fire(RefreshColumns(event.canvasName, setOf(event.card.metadata.columnId)))
        }

        eventBus.on<ColumnEdited> { event ->
            // The editor rebuilds its text from cards and omits the column's style header; preserve
            // the existing header so editing cards doesn't drop the column's wallpaper/colour.
            val style = model.getColumnStyle(event.canvasName, event.columnId)
            model.updateColumn(event.canvasName, event.columnId,
                MarkdownService.columnStyleHeader(style) + MarkdownService.stripColumnStyle(event.markdown))
        }

        eventBus.on<ClearDone> { event ->
            val changed = model.clearDone(event.canvasName)
            if (changed.isNotEmpty()) {
                eventBus.fire(RefreshColumns(event.canvasName, changed.toSet()))
            }
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
                val (updated, _) = ColumnSplit.deleteColumn(canvas, event.columnId)
                model.deleteColumn(event.canvasName, event.columnId)   // discard the column's cards
                if (updated != canvas) {
                    model.addCanvas(updated)        // persist rearranged layout (overwrites)
                }
                // When ColumnSplit refuses (the last remaining column), the layout is unchanged but we
                // still cleared its cards above — so the canvas drops back to the empty "welcome" state.
                model.changeCanvas(event.canvasName)
                eventBus.fire(RefreshCanvas(event.canvasName))
            }
        }
    }

    /** Apply a layout transform to a canvas, persist it (overwrites), and re-render. */
    private fun applyLayout(
        model: Model,
        eventBus: EventBus,
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