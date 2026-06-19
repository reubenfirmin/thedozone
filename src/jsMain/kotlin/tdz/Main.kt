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

val rootCanvas = CanvasName("")

/**
 * The single composition root: constructs and holds the app's collaborators (Model, EventBus,
 * Controller, DragDrop, Index) and wires them together explicitly, instead of scattering them as
 * free-floating top-level singletons read ambiently across the codebase. Built once on first
 * access (route registration / [main]); the route objects and [main] reach their collaborators
 * through here.
 */
object App {
    val model = Model(rootCanvas)
    val eventBus = EventBus<Event>()
    val controller = Controller(eventBus, model)
    val dragDrop = DragDrop(model, eventBus)
    val index = Index(model, eventBus, dragDrop)
}

// Compatibility accessor for the route/keyboard tests, which still reach for the model by name.
// It forwards to [App] — the wiring itself no longer flows through it. (DragDrop is now threaded
// explicitly App -> Index -> CanvasView -> CanvasColumns, so no global forwarder is needed.)
val tdzModel: Model get() = App.model

/** The canvas itself: the board at "/" or any nested board path. */
object canvasPages : Routes<Index>(App.index, "/") {

    val home = route(mode = RenderMode.PARTIAL) { "/" to {
        // "/" IS the root board — dispatch through the controller (same as the board route) so root
        // becomes current. Without this, navigating to "/" (e.g. after deleting the current board)
        // would leave `currentCanvas` pointing at the now-deleted board.
        index(App.controller.openCanvasPath(rootCanvas))
    } }.title { "The Do Zone" }

    val board = route(mode = RenderMode.PARTIAL) { "/{canvas...}" to { params ->
        val name = CanvasName(params.segments("canvas").joinToString("/"))
        // Any path is valid: the controller builds the missing chain (idempotent) and makes it
        // current; the view renders. The route only maps URL -> action -> render.
        index(App.controller.openCanvasPath(name))
    } }.title { params ->
        CanvasName(params.segments("canvas").joinToString("/")).leaf()
            .ifEmpty { null }?.let { "$it — The Do Zone" } ?: "The Do Zone"
    }
}

/** Help topics render into the overlay zone; on a cold deep-link the canvas route runs first. */
object helpPages : Routes<Index>(App.index, "/help") {

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

/** The privacy policy: a clean top-level `/privacy` (linked externally, e.g. the Dropbox app
 *  listing), rendered into the overlay like the help docs. */
object legalPages : Routes<Index>(App.index, "/privacy") {

    val privacy = fragment(Zones.OVERLAY, canvasPages.home, mode = RenderMode.PARTIAL) {
        "" to { _ -> helpPage(HelpTopic.PRIVACY) }
    }.title { "Privacy" }
}

/** Settings open body-level modals (their own scrims), so these stay plain routes. */
object settingsPages : Routes<Index>(App.index, "/settings") {

    val root = route(mode = RenderMode.PARTIAL) { "" to { Router.navigate(sync.path()) } }

    val sync = route(mode = RenderMode.PARTIAL) { "/sync" to { openSyncSettings() } }
        .title { "Cloud Sync" }

    val folder = route(mode = RenderMode.PARTIAL) { "/folder" to { openFolderSettings() } }
        .title { "Local Folder Sync" }
}

fun main() {
    // Apply the saved theme (or the default — Rainbow Gaps) before the first render so its
    // colors/font and the theme-color meta apply immediately.
    val initialTheme = App.model.getTheme()?.value?.let { saved ->
        themes.firstOrNull { it.name == saved }
            ?: longFormTheme.takeIf { saved == "Immaterial" }   // theme renamed; honor old saves
    } ?: ThemeManager.current
    ThemeManager.use(initialTheme)
    // Debug instrumentation (verbose console logging + sync logs) is off in production, enabled
    // on demand via the `?debug` URL flag.
    val debugEnabled = kotlinx.browser.window.location.search
        .removePrefix("?").split("&").any { it == "debug" || it.startsWith("debug=") }
    if (debugEnabled) zoned.framework.Debugger.debugOn()
    tdz.sync.SyncLog.enabled = debugEnabled
    App.model.purgeOrphans().let { (purged, orphanContent) ->
        zoned.framework.Debugger.debug("[storage] purged $purged orphaned mode/legacy key(s); " +
            "${orphanContent.size} orphaned content column(s) left for review: $orphanContent")
    }
    tdz.model.localstorage.StorageDump.dump()   // debug-gated; prints only with ?debug
    tdz.sync.SyncBootstrap.init(App.model, App.eventBus)
    // Passing the collections IS their registration — an unmounted collection contributes no
    // routes, and any typed Route.path() link to it fails loudly naming the missing mount.
    Router.start(canvasPages, helpPages, settingsPages, legalPages)
}

const val ACE_TAB_SIZE = 2
val mapLog = LogType("maps", Color.black, Color.white)
