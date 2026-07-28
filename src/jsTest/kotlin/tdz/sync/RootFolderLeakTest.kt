package tdz.sync

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.Event
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Split
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.localstorage.SyncConfigDoc
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for the reported leak: with a configured sync folder ("thedozone2"), NOTHING may
 * be written to the Dropbox app root. Every uploaded path — including the ROOT/home canvas (empty
 * name) and nested child boards — must live under "/thedozone2/…".
 */
@OptIn(DelicateCoroutinesApi::class)
class RootFolderLeakTest {

    private class RecordingStorage : RemoteStorage {
        val files = mutableMapOf<String, Pair<RemoteFile, String>>()
        val uploads = mutableListOf<String>()
        private var rev = 0
        override suspend fun list(folder: String) = RemoteListing(files.values.map { it.first }, rootExists = true)
        override suspend fun download(path: String) = files.getValue(path).let { it.second to it.first }
        override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
            uploads += path
            rev++
            val f = RemoteFile(path, "rev$rev", rev.toDouble())
            files[path] = f to content
            return f
        }
        override suspend fun delete(path: String) { files.remove(path) }
        override suspend fun revoke() {}
        override suspend fun columnVv(path: String): VersionVector = emptyMap()
    }

    @Test
    fun cloudProvidersDefaultToAConcreteFolder() {
        // A blank field must still land in a clean namespace, never the provider root (Dropbox's
        // app-folder root litters/collides; a blank Drive root scatters canvases). So both cloud
        // providers supply a default — the fix for the "Dropbox can't sync to its app-folder root"
        // error a user hit by leaving the folder blank. (The start()-time safeguard below still
        // refuses an explicitly-blank stored config, as defense in depth.)
        assertTrue(SyncProviders.defaultRoot(SyncProvider.DROPBOX).isNotBlank(), "Dropbox needs a default folder")
        assertTrue(SyncProviders.defaultRoot(SyncProvider.GOOGLE_DRIVE).isNotBlank(), "Drive needs a default folder")
    }

    @Test
    fun dropboxRefusesToSyncWhenFolderIsBlank(): dynamic = GlobalScope.promise {
        // The safeguard: a blank Dropbox folder resolves to the shared app-folder ROOT, which mixes
        // every board (and collides with other instances). Dropbox must REFUSE to sync rather than
        // scatter into the root. (Drive is exempt — it always has a concrete OAuth-scoped folder.)
        val canvas = CanvasName("_dbx-rootguard")
        val model = Model(canvas)
        val bus = EventBus<Event>()
        val storage = RecordingStorage()
        model.writeSyncConfig(SyncConfigDoc(
            provider = SyncProvider.DROPBOX, enabled = true, rootFolder = "",   // blank == app root
            refreshToken = "t", accountEmail = "x",
        ))
        model.writeSyncState(SyncStateDoc.EMPTY)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        model.updateColumn(canvas, ColumnId("1"), "# Home\n* work\n")
        val c = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
        try {
            c.start()
            delay(80)
            bus.fire(ColumnsChanged(canvas, setOf(ColumnId("1"))))
            delay(60)

            assertTrue(storage.uploads.isEmpty(),
                "Dropbox must NOT write to the app root with a blank folder; uploaded: ${storage.uploads}")
            assertTrue(SyncStateStore.current is SyncState.Error,
                "a blank Dropbox folder surfaces a config error, got ${SyncStateStore.current}")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }

    @Test
    fun connectWithAFolderNeverWritesToTheAppRoot(): dynamic = GlobalScope.promise {
        val rootCanvas = CanvasName("")
        val model = Model(rootCanvas)
        val bus = EventBus<Event>()
        val storage = RecordingStorage()
        model.writeSyncConfig(SyncConfigDoc(
            provider = SyncProvider.DROPBOX, enabled = false, rootFolder = "thedozone2",
            refreshToken = null, accountEmail = null,
        ))
        model.writeSyncState(SyncStateDoc.EMPTY)
        // A home (root) board with content...
        model.addCanvas(canvasFromTemplate(rootCanvas, null, SINGLE_PANE))
        model.updateColumn(rootCanvas, ColumnId("1"), "# Home\n* work\n")
        // ...and a nested child board "fish".
        val fish = CanvasName("fish")
        model.addCanvas(Canvas(fish, rootCanvas, listOf(ColumnId("0"), ColumnId("1")),
            listOf(ColumnLayoutSpec(ColumnId("0"), Split.VERTICAL, listOf(ColumnId("1"))))))
        model.updateColumn(fish, ColumnId("1"), "# Fish\n* swim\n")

        val c = SyncController(model, bus, storageOverride = storage)
        c.connectOverride = { ConnectResult(accountEmail = null, refreshToken = "test-token") }
        try {
            c.connect()
            delay(250)
            // And a subsequent edit-driven push.
            bus.fire(ColumnsChanged(rootCanvas, setOf(ColumnId("1"))))
            delay(80)

            assertTrue(storage.uploads.isNotEmpty(), "setup: something was pushed")
            val leaked = storage.uploads.filterNot { it.startsWith("/thedozone2/") }
            assertTrue(leaked.isEmpty(),
                "NOTHING may be written outside the configured folder — leaked to app root: $leaked")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }
}
