package tdz.sync

import kotlinx.browser.document
import org.w3c.dom.events.KeyboardEvent
import tdz.controller.events.EventBus
import tdz.model.Model
import tdz.view.sync.LocalFolderModal
import tdz.view.sync.SyncModal
import tdz.view.sync.SyncIndicator

/**
 * Single entry point that assembles the cloud-sync vertical without touching other agents' files.
 * Builds the controller, modal, and self-healing indicator; starts reconcile; and installs an
 * interim Ctrl+Shift+D trigger to open the modal alongside the context-menu entry.
 */
object SyncBootstrap {

    lateinit var controller: SyncController
        private set
    lateinit var modal: SyncModal
        private set
    lateinit var localFolderModal: LocalFolderModal
        private set

    fun init(model: Model, eventBus: EventBus) {
        controller = SyncController(model, eventBus)
        val dismiss: () -> Unit = { zoned.framework.routing.Router.navigate(tdz.App.index.resource.currentCanvasUrl()) }
        modal = SyncModal(model, eventBus, controller, onDismiss = dismiss)
        localFolderModal = LocalFolderModal(model, eventBus, controller, onDismiss = dismiss)
        SyncIndicator(eventBus) {
            if (kotlinx.browser.window.location.pathname != "/settings/sync") {
                zoned.framework.routing.Router.navigate("/settings/sync")
            }
        }.attach()

        document.addEventListener("keydown", { event ->
            val e = event as KeyboardEvent
            if (e.ctrlKey && e.shiftKey && (e.key == "D" || e.key == "d")) {
                e.preventDefault()
                if (kotlinx.browser.window.location.pathname != "/settings/sync") {
                    zoned.framework.routing.Router.navigate("/settings/sync")
                }
            }
        })

        controller.start()   // no-ops when not configured
    }

    /** Hide any open sync modal — used by the canvas route when returning from a /settings route. */
    fun dismissModals() {
        if (::modal.isInitialized) modal.hide()
        if (::localFolderModal.isInitialized) localFolderModal.hide()
    }
}
