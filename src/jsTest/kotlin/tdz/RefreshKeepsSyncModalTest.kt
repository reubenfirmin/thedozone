package tdz

import tdz.controller.events.Event
import tdz.controller.events.RefreshCanvas
import tdz.model.Model
import tdz.model.localstorage.SyncConfigDoc
import tdz.sync.SyncBootstrap
import tdz.view.Zones
import web.dom.document
import zoned.framework.events.EventBus
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The /settings/sync URL-stranding bug: while the sync modal is open the URL is /settings/sync,
 * and DISMISSING the modal is what navigates back to the canvas URL. During a reconnect the pull
 * fires [RefreshCanvas], and the canvas re-render used to dismiss the modal as a side effect —
 * no navigation ran, so the location bar stayed at /settings/sync. Only ROUTE renders may dismiss
 * overlays; sync-triggered refreshes must leave the modal (and therefore the URL contract) alone.
 */
class RefreshKeepsSyncModalTest {

    @Test
    fun syncTriggeredCanvasRefreshDoesNotDismissTheSyncModal() {
        val bus = EventBus<Event>()
        val model = Model(rootCanvas)
        model.writeSyncConfig(SyncConfigDoc.DISABLED)   // isolate: the config key is a singleton
        val index = Index(model, bus)
        SyncBootstrap.init(model, bus)
        fun bodyText() = document.body.textContent ?: ""
        try {
            index.index(model.currentCanvas())
            SyncBootstrap.modal.show()
            assertTrue(bodyText().contains("Cloud Sync"), "setup: the sync modal is open")

            // What a mid-reconnect pulled manifest fires (Index re-renders the canvas zone).
            bus.fire(RefreshCanvas(model.currentCanvas().name, remote = true))

            assertTrue(bodyText().contains("Cloud Sync"),
                "a sync-triggered canvas refresh must NOT dismiss the modal — dismissal is what " +
                "navigates away from /settings/sync, so this stranded the URL there")
        } finally {
            SyncBootstrap.dismissModals()
            Zones.CANVAS.resolve()?.remove()
            Zones.INTRO.resolve()?.remove()
        }
    }
}
