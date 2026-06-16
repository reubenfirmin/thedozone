package tdz.controller

import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.controller.io.CanvasImporter
import tdz.controller.io.FilePicker
import tdz.controller.io.ImportMode
import tdz.controller.io.TdzDocument
import tdz.model.Model

/**
 * Orchestrates uploading a `.tdz` (or plain markdown) file into the model. The actual work is split
 * across testable pieces: [FilePicker] (DOM file dialog), [TdzDocument.decode] (format), and
 * [CanvasImporter] (placement). This class just wires them together and refreshes the view.
 */
class Uploader(val model: Model, val eventBus: EventBus<Event>) {

    fun upload(mode: ImportMode) {
        FilePicker.pick(".tdz") { name, content ->
            val result = CanvasImporter(model).import(name, TdzDocument.decode(content), mode, model.currentCanvas())
            // HERE-into-a-blank-board can rewrite the board's layout; reload the cached currentCanvas
            // so the re-render reflects the adopted columns (RefreshCanvas renders from the cache).
            model.changeCanvas(result.canvas)
            eventBus.fire(RefreshCanvas(result.canvas))
        }
    }
}
