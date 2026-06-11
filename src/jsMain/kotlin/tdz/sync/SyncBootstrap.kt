package tdz.sync

import kotlinx.browser.window
import tdz.canvasPages
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.model.Model
import tdz.view.sync.LocalFolderModal
import tdz.view.sync.SyncModal
import tdz.view.sync.SyncIndicator
import web.dom.document
import web.events.EventType
import web.events.addEventListener
import web.keyboard.KeyboardEvent
import zoned.framework.routing.Router

/**
 * Single entry point that assembles the cloud-sync vertical without touching other agents' files.
 * Builds the controller, modal, and indicator (a permanent body-level zone — canvas refreshes swap
 * only the canvas zone, so it needs no self-healing); starts reconcile; and installs an interim
 * Ctrl+Shift+D trigger to open the modal alongside the context-menu entry.
 */
object SyncBootstrap {

    lateinit var controller: SyncController
        private set
    lateinit var modal: SyncModal
        private set
    lateinit var localFolderModal: LocalFolderModal
        private set

    fun init(model: Model, eventBus: EventBus<Event>) {
        controller = SyncController(model, eventBus)
        val dismiss: () -> Unit = { Router.navigate(canvasPages.resource.currentCanvasUrl()) }
        modal = SyncModal(model, eventBus, controller, onDismiss = dismiss)
        localFolderModal = LocalFolderModal(model, eventBus, controller, onDismiss = dismiss)
        SyncIndicator(eventBus) { openSyncSettings() }.attach()

        document.addEventListener(EventType<KeyboardEvent>("keydown"), { e ->
            if (e.ctrlKey && e.shiftKey && (e.key == "D" || e.key == "d")) {
                e.preventDefault()
                openSyncSettings()
            }
        })

        controller.start()   // no-ops when not configured
    }

    private fun openSyncSettings() {
        if (window.location.pathname != "/settings/sync") {
            Router.navigate("/settings/sync")
        }
    }

    /** Hide any open sync modal — used by the canvas route when returning from a /settings route. */
    fun dismissModals() {
        if (::modal.isInitialized) modal.hide()
        if (::localFolderModal.isInitialized) localFolderModal.hide()
    }
}
