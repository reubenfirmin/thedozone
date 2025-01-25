package tdz

import kotlinx.css.Color
import tdz.controller.Controller
import tdz.controller.events.EventBus
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.*
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.view.CanvasEventHandlers
import tdz.view.Index
import zoned.framework.*
import zoned.framework.routing.Router
import zoned.framework.routing.Routes

val rootCanvas = CanvasName("_")
val tdzModel = Model(rootCanvas)
val eventBus = EventBus()
val controller = Controller(tdzModel, eventBus)

class App {

    object index : Routes<Index>(Index(tdzModel, CanvasEventHandlers(eventBus), eventBus), "/") {
        val home = route {
            "/" to {
                val canvas = tdzModel.getCanvas(rootCanvas)
                if (canvas == null) {
                    tdzModel.addCanvas(canvasFromTemplate(rootCanvas, null, SINGLE_PANE))
                    index(tdzModel.getCanvas(rootCanvas)!!)
                } else {
                    index(canvas)
                }
            }
        }
        val canvas = route {
            "/{canvas}" to { params ->
                {
                    console.log("Navigating to ${params["canvas"]}")
                    val canvas = tdzModel.getCanvas(CanvasName(params["canvas"]!!))
                    if (canvas == null) {
                        Router.navigate("/")
                    } else {
                        index(canvas)
                    }
                }
            }
        }
    }

    init {
        // turn off for production builds
        Debugger.debugOn()
        // hacky. init (/prevent dce?)
        listOf(index).forEach { _ ->
            console
        }
    }
}

fun main() {
    App()
    Router.start()
}

const val ACE_TAB_SIZE = 2
val mapLog = LogType("maps", Color.black, Color.white)
val eventLog = LogType("events", Color.black, Color.yellow)