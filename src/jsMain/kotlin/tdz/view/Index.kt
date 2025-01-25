package tdz.view

import kotlinx.html.TagConsumer
import kotlinx.html.div
import tdz.controller.events.EventBus
import tdz.model.Canvas
import tdz.model.Model
import web.dom.document
import zoned.framework.dom.ensureId
import zoned.framework.dom.onKeyDown
import zoned.framework.dom.onKeyPress
import zoned.framework.dom.onMount
import zoned.framework.dom.onMouseOver
import zoned.framework.interop.classes
import zoned.framework.interop.el

class Index(val model: Model, val events: CanvasEventHandlers, val eventBus: EventBus) {

    fun TagConsumer<*>.index(canvas: Canvas) {
        document.body.classes("bg-[#1a2b3c] bg-[radial-gradient(circle,_#243b55_0%,_#141e30_100%)] min-h-screen) text-white")
        div {
            ensureId()
            val columns = CanvasColumns(canvas, events, eventBus) {
                id -> model.cards(canvas.name, id)
            }
            onMouseOver { e ->
                //console.log("Mouse")
            }
            onKeyDown { e ->
                //console.log("Key ${e.code}")
                events.handleEvent(e, canvas, columns.focused())
            }
            onMount {
                el(this)?.let {
                    it.tabIndex = -1
                    it.focus()
                }
            }
            with (columns) {
                render()
            }
        }
    }
}