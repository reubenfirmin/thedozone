package tdz.view.sync

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import tdz.controller.events.EventBus
import tdz.model.CanvasName
import tdz.model.Model
import tdz.model.localstorage.SyncConfigDoc
import tdz.sync.SyncController
import tdz.sync.SyncProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncModalInputTest {

    @Test
    fun typedFolderSurvivesProviderSwitchRerender() {
        val model = Model(CanvasName("_syncmodal-test"))
        // Known-disconnected baseline so the provider chooser renders (other tests share localStorage).
        model.writeSyncConfig(
            SyncConfigDoc(SyncProvider.DROPBOX, enabled = false, rootFolder = "", refreshToken = null, accountEmail = null),
        )
        val bus = EventBus()
        val modal = SyncModal(model, bus, SyncController(model, bus))
        try {
            modal.show()
            val input = document.getElementById("tdz-sync-root") as HTMLInputElement
            input.value = "my-folder"

            // Clicking a provider chip re-renders the dialog; the typed folder must survive.
            val chip = document.body!!.querySelectorAll("div").asList()
                .filterIsInstance<HTMLElement>()
                .last { it.textContent == SyncProvider.GOOGLE_DRIVE.displayName }
            chip.click()

            val after = document.getElementById("tdz-sync-root") as HTMLInputElement
            assertEquals("my-folder", after.value, "typed folder should survive the provider-switch re-render")
        } finally {
            modal.hide()
        }
    }
}
