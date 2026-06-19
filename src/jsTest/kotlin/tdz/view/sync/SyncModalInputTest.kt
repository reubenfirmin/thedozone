package tdz.view.sync

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.model.CanvasName
import tdz.model.Model
import tdz.model.localstorage.SyncConfigDoc
import tdz.sync.SyncController
import tdz.sync.SyncProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncModalInputTest {

    private class NoopStorage : tdz.sync.RemoteStorage {
        override suspend fun list(folder: String) = tdz.sync.RemoteListing(emptyList(), rootExists = true)
        override suspend fun download(path: String) = "" to tdz.sync.RemoteFile(path, "r", 0.0)
        override suspend fun upload(path: String, content: String, ifMatchRev: String?) = tdz.sync.RemoteFile(path, "r", 0.0)
        override suspend fun delete(path: String) {}
        override suspend fun revoke() {}
        override suspend fun columnVv(path: String): tdz.sync.VersionVector = emptyMap()
    }

    @Test
    fun connectPersistsTheTypedFolderSoSyncDoesNotWriteToTheAppRoot() {
        val model = Model(CanvasName("_syncmodal-persist"))
        model.writeSyncConfig(
            SyncConfigDoc(SyncProvider.DROPBOX, enabled = false, rootFolder = "", refreshToken = null, accountEmail = null),
        )
        val bus = EventBus<Event>()
        val controller = SyncController(model, bus, storageOverride = NoopStorage())
        controller.connectOverride = { tdz.sync.ConnectResult(accountEmail = "x", refreshToken = "t") }
        val modal = SyncModal(model, bus, controller)
        try {
            modal.show()
            (document.getElementById("tdz-sync-root") as HTMLInputElement).value = "thedozone2"
            val connect = document.body!!.querySelectorAll("div").asList()
                .filterIsInstance<HTMLElement>().last { it.textContent == "Connect" }
            connect.click()

            assertEquals("thedozone2", model.readSyncConfig().rootFolder,
                "the typed folder MUST persist on Connect — a blank folder makes sync write to the app root")
        } finally {
            modal.hide()
            tdz.sync.SyncStateStore.set(tdz.sync.SyncState.NotConfigured, bus)
        }
    }

    @Test
    fun typedFolderSurvivesProviderSwitchRerender() {
        val model = Model(CanvasName("_syncmodal-test"))
        // Known-disconnected baseline so the provider chooser renders (other tests share localStorage).
        model.writeSyncConfig(
            SyncConfigDoc(SyncProvider.DROPBOX, enabled = false, rootFolder = "", refreshToken = null, accountEmail = null),
        )
        val bus = EventBus<Event>()
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

class SyncModalReconnectTest {

    /**
     * The beta bug: the status line updates live (statusRef), but the BUTTON ROW only re-rendered
     * when connectedness flipped — so when Google's session lapsed mid-session the modal said
     * "Reconnection needed" while still offering "Sync now" instead of "Reconnect".
     */
    @Test
    fun lapsedAuthSwapsTheButtonRowToReconnect() {
        val model = Model(CanvasName("_syncmodal-reconnect"))
        model.writeSyncConfig(SyncConfigDoc(
            SyncProvider.GOOGLE_DRIVE, enabled = true, rootFolder = "/x",
            refreshToken = null, accountEmail = "rf@test",
        ))
        val bus = EventBus<Event>()
        val modal = SyncModal(model, bus, SyncController(model, bus))
        try {
            tdz.sync.SyncStateStore.set(tdz.sync.SyncState.Synced, bus)
            modal.show()
            fun buttons() = document.body!!.querySelectorAll("div").asList()
                .filterIsInstance<HTMLElement>().map { it.textContent }
            assertEquals(false, buttons().contains("Reconnect"), "healthy session: no Reconnect button")

            tdz.sync.SyncStateStore.set(tdz.sync.SyncState.NeedsReconnect, bus)

            assertEquals(true, buttons().contains("Reconnect"),
                "a lapsed session must swap the button row to offer Reconnect")
        } finally {
            modal.hide()
            tdz.sync.SyncStateStore.set(tdz.sync.SyncState.NotConfigured, bus)
        }
    }
}
