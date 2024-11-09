package tdz

import kotlinx.html.TagConsumer
import kotlinx.html.div
import tdz.model.Canvas
import tdz.model.Model
import tdz.view.CanvasColumns
import web.dom.document
import zoned.framework.interop.classes

class Index(val model: Model) {

    fun TagConsumer<*>.index(canvas: Canvas) {
        document.body.classes("bg-[#1a2b3c] bg-[radial-gradient(circle,_#243b55_0%,_#141e30_100%)] min-h-screen) text-white")
        div {
            val columns = CanvasColumns(canvas, { id -> model.cards(canvas.name, id) })
            with (columns) {
                render(canvas.columns)
            }
        }
    }
}

