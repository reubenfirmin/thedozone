    package tdz

import kotlinx.css.Color
import tdz.controller.Controller
import tdz.controller.events.EventBus
import tdz.model.CanvasName
import tdz.model.Model
import zoned.framework.*
import zoned.framework.routing.Router
import zoned.framework.routing.Routes

val rootCanvas = CanvasName("_")
val tdzModel = Model(rootCanvas)
val eventBus = EventBus()
val tdzController = Controller(eventBus, tdzModel)

class App {

        object index: Routes<Index>(Index(tdzModel, eventBus), "/") {
            val home = route { "/" to {
                index(tdzModel.getCanvas(rootCanvas)!!)
            }
        }
        val canvas = route {
            "/{canvas}" to {
                params -> {
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
        // hacky. init
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