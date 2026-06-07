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
        modal = SyncModal(model, eventBus, controller)
        localFolderModal = LocalFolderModal(model, eventBus, controller)
        SyncIndicator(eventBus) { modal.show() }.attach()

        document.addEventListener("keydown", { event ->
            val e = event as KeyboardEvent
            if (e.ctrlKey && e.shiftKey && (e.key == "D" || e.key == "d")) {
                e.preventDefault()
                modal.show()
            }
        })

        controller.start()   // no-ops when not configured
    }
}
