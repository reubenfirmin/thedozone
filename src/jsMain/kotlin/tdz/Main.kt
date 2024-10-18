package tdz

import kotlinx.css.Color
import org.example.zoned.framework.Debugger
import org.example.zoned.framework.LogType
import zoned.framework.Router
import zoned.framework.route

fun main() {

    // turn off for production builds
    Debugger.debugOn()

    route(Index()) {
        "/" to { index() }
    }

    Router.start()
}

const val ACE_TAB_SIZE = 2
val mapLog = LogType("maps", Color.black, Color.white)