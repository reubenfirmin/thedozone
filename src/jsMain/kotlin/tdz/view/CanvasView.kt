package tdz.view

import kotlinx.css.pct
import kotlinx.css.height
import kotlinx.css.width
import kotlinx.html.TagConsumer
import kotlinx.html.div
import zoned.framework.interop.css
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshCard
import tdz.controller.events.RefreshColumns
import tdz.model.Canvas
import tdz.model.Model
import web.html.HTMLElement
import zoned.framework.routing.ZoneView

/**
 * The canvas zone: renders a [Canvas] as its column layout. [ZoneView] owns the lifecycle — every
 * [swap] first saves the previous render's open editors and detaches its [RefreshColumns]
 * subscription, so stale renders can never fire against a DOM they no longer own.
 */
class CanvasView(bus: EventBus<Event>, private val model: Model) : ZoneView<Canvas, Event>(Zones.CANVAS, bus) {

    /** The live render's column controller (keyboard/menu target), or null between renders. */
    var columns: CanvasColumns? = null
        private set

    override fun TagConsumer<HTMLElement>.render(canvas: Canvas) {
        val cols = CanvasColumns(canvas, bus, model) { id -> model.cards(canvas.name, id) }
        columns = cols
        // Synchronous on purpose (no rAF batching): no producer refreshes the same column twice in
        // a tick (toggles use RefreshCard, single-card drops skip refresh), and a sync pull's burst
        // of distinct columns already shares one style/layout/paint pass at frame end.
        on<RefreshColumns> { e -> if (e.canvasName == canvas.name) cols.refreshColumns(e.columnIds) }
        on<RefreshCard> { e -> if (e.canvasName == canvas.name) cols.refreshCard(e.ref) }
        onDispose {
            cols.saveEditors()
            columns = null
        }
        div {
            css { width = 100.pct; height = 100.pct }
            with(cols) { render(canvas.columns.filter { id -> !canvas.layoutMap.containsKey(id) }) }
        }
    }
}
