package tdz

import kotlinx.html.TagConsumer
import kotlinx.html.div
import tdz.model.Canvas
import web.dom.document
import zoned.framework.interop.classes

class Index {

    fun TagConsumer<*>.index(canvas: Canvas) {
        document.body.classes("bg-[#1a2b3c] bg-[radial-gradient(circle,_#243b55_0%,_#141e30_100%)] min-h-screen)")
        div {

        }
    }
}

