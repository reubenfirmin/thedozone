package tdz

import kotlinx.css.Color
import kotlinx.html.TagConsumer
import kotlinx.html.div
import org.example.zoned.framework.LogType
import web.dom.document
import zoned.framework.interop.classes

class Index {

    fun TagConsumer<*>.index() {
        document.body.classes("bg-[#1a2b3c] bg-[radial-gradient(circle,_#243b55_0%,_#141e30_100%)] min-h-screen)")
        div {

        }
    }
}

