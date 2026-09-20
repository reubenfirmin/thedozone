package tdz.controller.io

import tdz.controller.events.ColumnsChanged
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.model.Model

/**
 * Orchestrates uploading a `.tdz` (or plain markdown) file into the model. The actual work is split
 * across testable pieces: [FilePicker] (DOM file dialog), [TdzDocument.decode] (format), and
 * [CanvasImporter] (placement). This class just wires them together and refreshes the view.
 */
class Uploader(val model: Model, val eventBus: EventBus<Event>) {

    fun upload(mode: ImportMode) {
        FilePicker.pick(".tdz") { name, content -> applyImport(name, content, mode) }
    }

    /**
     * Decode + place an uploaded file, then make the change visible to BOTH the view and sync.
     * Split out of [upload] so the placement+event wiring is testable without a DOM file dialog.
     *
     * The importer writes column content through the model directly, which fires no events; firing
     * [ColumnsChanged] for the touched columns stamps them as a real local change (pending +
     * version-vector bump, via the SyncController) so the upload is treated as authoritative —
     * pushed up, never silently reverted by a stale remote. [RefreshCanvas] (structural) covers the
     * manifest and re-renders from the reloaded cache (HERE-into-a-blank-board can rewrite layout).
     */
    internal fun applyImport(name: String, content: String, mode: ImportMode) {
        val result = CanvasImporter(model).import(name, TdzDocument.decode(content), mode, model.currentCanvas())
        model.changeCanvas(result.canvas)
        if (result.changedColumns.isNotEmpty())
            eventBus.fire(ColumnsChanged(result.canvas, result.changedColumns.toSet()))
        eventBus.fire(RefreshCanvas(result.canvas))
    }
}
