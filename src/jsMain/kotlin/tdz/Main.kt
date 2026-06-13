package tdz

import kotlinx.css.Color
import tdz.controller.Controller
import tdz.controller.DragDrop
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.model.CanvasName
import tdz.model.Model
import tdz.view.Zones
import tdz.view.help.HelpTopic
import tdz.view.theme.ThemeManager
import tdz.view.theme.longFormTheme
import tdz.view.theme.themes
import zoned.framework.*
import zoned.framework.routing.RenderMode
import zoned.framework.routing.Router
import zoned.framework.routing.Routes

val rootCanvas = CanvasName("_")
val tdzModel = Model(rootCanvas)
val eventBus = EventBus<Event>()
val tdzController = Controller(eventBus, tdzModel)
val tdzDragDrop = DragDrop(tdzModel, eventBus)
val tdzIndex = Index(tdzModel, eventBus)

/** The canvas itself: the board at "/" or any nested board path. */
object canvasPages : Routes<Index>(tdzIndex, "/") {

    val home = route(mode = RenderMode.PARTIAL) { "/" to {
        index(tdzModel.currentCanvas())
    } }.title { "The Do Zone" }

    val board = route(mode = RenderMode.PARTIAL) { "/{canvas...}" to { params ->
        val segments = params.segments("canvas")
        val canvasName = segments.lastOrNull() ?: ""

        val canvas = tdzModel.getCanvas(CanvasName(canvasName))
        if (canvas == null) {
            Router.navigate("/")
        } else {
            index(canvas)
        }
    } }.title { params ->
        params.segments("canvas").lastOrNull()?.let { "$it — The Do Zone" } ?: "The Do Zone"
    }
}

/** Help topics render into the overlay zone; on a cold deep-link the canvas route runs first. */
object helpPages : Routes<Index>(tdzIndex, "/help") {

    val root = route(mode = RenderMode.PARTIAL) { "" to { Router.navigate(shortcuts.path()) } }

    val shortcuts = fragment(Zones.OVERLAY, canvasPages.home, mode = RenderMode.PARTIAL) {
        "/shortcuts" to { _ -> helpPage(HelpTopic.SHORTCUTS) }
    }.title { "Keyboard Shortcuts" }

    val formatting = fragment(Zones.OVERLAY, canvasPages.home, mode = RenderMode.PARTIAL) {
        "/formatting" to { _ -> helpPage(HelpTopic.FORMATTING) }
    }.title { "Card Formatting" }

    val releaseNotes = fragment(Zones.OVERLAY, canvasPages.home, mode = RenderMode.PARTIAL) {
        "/release-notes" to { _ -> helpPage(HelpTopic.RELEASE_NOTES) }
    }.title { "Release Notes" }
}

/** Settings open body-level modals (their own scrims), so these stay plain routes. */
object settingsPages : Routes<Index>(tdzIndex, "/settings") {

    val root = route(mode = RenderMode.PARTIAL) { "" to { Router.navigate(sync.path()) } }

    val sync = route(mode = RenderMode.PARTIAL) { "/sync" to { openSyncSettings() } }
        .title { "Cloud Sync" }

    val folder = route(mode = RenderMode.PARTIAL) { "/folder" to { openFolderSettings() } }
        .title { "Local Folder Sync" }
}

fun main() {
    // Restore the saved theme before the first render so its colors/font apply immediately.
    tdzModel.getTheme()?.let { saved ->
        themes.firstOrNull { it.name == saved }
            ?: longFormTheme.takeIf { saved == "Immaterial" }   // theme renamed; honor old saves
    }?.let { ThemeManager.use(it) }
    zoned.framework.Debugger.debugOn()          // debug on
    tdzModel.purgeOrphans().let { (purged, orphanContent) ->
        zoned.framework.Debugger.debug("[storage] purged $purged orphaned mode/legacy key(s); " +
            "${orphanContent.size} orphaned content column(s) left for review: $orphanContent")
    }
    tdz.model.localstorage.StorageDump.dump()   // debug-gated; prints only with ?debug
    tdz.sync.SyncBootstrap.init(tdzModel, eventBus)
    // Passing the collections IS their registration — an unmounted collection contributes no
    // routes, and any typed Route.path() link to it fails loudly naming the missing mount.
    Router.start(canvasPages, helpPages, settingsPages)
}

const val ACE_TAB_SIZE = 2
val mapLog = LogType("maps", Color.black, Color.white)
