    package tdz

import kotlinx.css.Color
import tdz.controller.Controller
import tdz.controller.DragDrop
import tdz.controller.events.EventBus
import tdz.model.CanvasName
import tdz.model.Model
import tdz.view.theme.ThemeManager
import tdz.view.theme.themes
import zoned.framework.*
import zoned.framework.libs.initLeaflet
import zoned.framework.routing.RenderMode
import zoned.framework.routing.Router
import zoned.framework.routing.Routes

val rootCanvas = CanvasName("_")
val tdzModel = Model(rootCanvas)
val eventBus = EventBus()
val tdzController = Controller(eventBus, tdzModel)
val tdzDragDrop = DragDrop(tdzModel, eventBus)

class App {

        object index: Routes<Index>(Index(tdzModel, eventBus), "/") {
            val home = route { "/" to {
                index(tdzModel.currentCanvas())
            }
        }.title { "The Do Zone" }
        val canvas = route(mode = RenderMode.PARTIAL) {
            "/{canvas...}" to { params ->
                val segments = params.segments("canvas")
                val canvasName = segments.lastOrNull() ?: ""

                val canvas = tdzModel.getCanvas(CanvasName(canvasName))
                if (canvas == null) {
                    Router.navigate("/")
                } else {
                    index(canvas)
                }
            }
        }.title { params ->
            params.segments("canvas").lastOrNull()?.let { "$it — The Do Zone" } ?: "The Do Zone"
        }

        val help = route(mode = RenderMode.PARTIAL) {
            "/help" to { Router.navigate("/help/shortcuts") }
        }

        val settings = route(mode = RenderMode.PARTIAL) {
            "/settings" to { Router.navigate("/settings/sync") }
        }

        val helpShortcuts = route(mode = RenderMode.PARTIAL) {
            "/help/shortcuts" to { openHelp(tdz.view.help.HelpTopic.SHORTCUTS) }
        }.title { "Keyboard Shortcuts" }

        val helpFormatting = route(mode = RenderMode.PARTIAL) {
            "/help/formatting" to { openHelp(tdz.view.help.HelpTopic.FORMATTING) }
        }.title { "Card Formatting" }

        val helpReleaseNotes = route(mode = RenderMode.PARTIAL) {
            "/help/release-notes" to { openHelp(tdz.view.help.HelpTopic.RELEASE_NOTES) }
        }.title { "Release Notes" }

        val settingsSync = route(mode = RenderMode.PARTIAL) {
            "/settings/sync" to { openSyncSettings() }
        }.title { "Cloud Sync" }

        val settingsFolder = route(mode = RenderMode.PARTIAL) {
            "/settings/folder" to { openFolderSettings() }
        }.title { "Local Folder Sync" }
    }

    init {
        // Debug mode off. Re-enable Debugger.debugOn() to show the column-number watermark + debug logs.
        // Debugger.debugOn()
        // hacky. init
        listOf(index).forEach { _ ->
            console
        }
    }
}

fun main() {
    initLeaflet()
    // Restore the saved theme before the first render so its colours/font apply immediately.
    tdzModel.getTheme()?.let { saved -> themes.firstOrNull { it.name == saved } }?.let { ThemeManager.use(it) }
    App()
    tdz.sync.SyncBootstrap.init(tdzModel, eventBus)
    Router.start()
}

const val ACE_TAB_SIZE = 2
val mapLog = LogType("maps", Color.black, Color.white)
val eventLog = LogType("events", Color.black, Color.yellow)