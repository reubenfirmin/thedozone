package tdz.sync

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import tdz.controller.events.Event
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.localstorage.CanvasDoc
import tdz.model.localstorage.SyncConfigDoc
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * connect() picks the first-sync direction itself: check the remote (empty → TO), then the local
 * device (no content AND no history → FROM); only when both sides have something does it park in
 * [SyncState.ChoosingDirection] for the user to choose.
 */
@OptIn(DelicateCoroutinesApi::class)
class ConnectDecisionTest {

    private class RecordingStorage(initial: List<Pair<RemoteFile, String>> = emptyList()) : RemoteStorage {
        val files = initial.associate { it.first.path to it }.toMutableMap()
        private var rev = 0
        override suspend fun list(folder: String) =
            RemoteListing(files.values.map { it.first }, rootExists = files.isNotEmpty())
        override suspend fun download(path: String) = files.getValue(path).let { it.second to it.first }
        override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
            rev++; val f = RemoteFile(path, "r$rev", rev.toDouble()); files[path] = f to content; return f
        }
        override suspend fun delete(path: String) { files.remove(path) }
        override suspend fun revoke() {}
        override suspend fun columnVv(path: String): VersionVector = emptyMap()
    }

    private fun setup(name: String, storage: RecordingStorage): Pair<Model, SyncController> {
        // hasLocalContent() scans ALL local boards; isolate from other suites' leftover localStorage.
        kotlinx.browser.window.localStorage.clear()
        val model = Model(CanvasName(name))
        val bus = EventBus<Event>()
        model.writeSyncConfig(SyncConfigDoc(SyncProvider.DROPBOX, enabled = false, rootFolder = "/$name", refreshToken = null, accountEmail = null))
        model.writeSyncState(SyncStateDoc.EMPTY)
        val c = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
        c.connectOverride = { ConnectResult(accountEmail = "rf@test", refreshToken = "token") }
        return model to c
    }

    @Test
    fun emptyRemoteAutoPushesLocalNoPrompt(): dynamic = GlobalScope.promise {
        val name = "_cd-empty-remote"
        val storage = RecordingStorage()
        val (model, c) = setup(name, storage)
        val canvas = CanvasName(name)
        val tmpl = canvasFromTemplate(canvas, null, SINGLE_PANE)
        model.addCanvas(tmpl)
        val col = tmpl.firstNonLayout()
        model.updateColumn(canvas, col, "# Mine\n* a\n")
        try {
            c.connect(); delay(160)
            assertTrue(SyncStateStore.current == SyncState.Synced, "empty remote → just push, no prompt: ${SyncStateStore.current}")
            assertTrue(storage.files.containsKey(SyncPaths.columnPath("/$name", canvas, col)), "local pushed up")
        } finally { SyncStateStore.set(SyncState.NotConfigured, EventBus()) }
    }

    @Test
    fun brandNewDeviceAutoMergesFromFolderNoPrompt(): dynamic = GlobalScope.promise {
        val name = "_cd-fresh"
        val canvas = CanvasName(name)
        val manifestPath = SyncPaths.manifestPath("/$name", canvas)
        val template = canvasFromTemplate(canvas, null, SINGLE_PANE)
        val col = template.firstNonLayout()   // the template's opaque content id (same on both sides here)
        val colPath = SyncPaths.columnPath("/$name", canvas, col)
        val storage = RecordingStorage(listOf(
            RemoteFile(manifestPath, "rM", 100.0) to CanvasDoc(canvas, null, template.columns, template.layouts).encode(),
            RemoteFile(colPath, "rC", 100.0) to "# Remote\n* stuff\n",
        ))
        val (model, c) = setup(name, storage)
        model.addCanvas(template)   // local board exists but is EMPTY (no content) and has NO history
        try {
            c.connect(); delay(180)
            assertTrue(SyncStateStore.current == SyncState.Synced, "no content + no history → auto FROM: ${SyncStateStore.current}")
            assertTrue((model.getCanvasColumnRaw(canvas, col) ?: "").contains("stuff"),
                "the folder's content merged into the brand-new device")
        } finally { SyncStateStore.set(SyncState.NotConfigured, EventBus()) }
    }

    @Test
    fun bothSidesHaveContentParksInChoosingDirection(): dynamic = GlobalScope.promise {
        val name = "_cd-conflict"
        val canvas = CanvasName(name)
        val template = canvasFromTemplate(canvas, null, SINGLE_PANE)
        val col = template.firstNonLayout()
        val colPath = SyncPaths.columnPath("/$name", canvas, col)
        val storage = RecordingStorage(listOf(
            RemoteFile(SyncPaths.manifestPath("/$name", canvas), "rM", 100.0) to CanvasDoc(canvas, null, template.columns, template.layouts).encode(),
            RemoteFile(colPath, "rC", 100.0) to "# Remote\n* theirs\n",
        ))
        val (model, c) = setup(name, storage)
        model.addCanvas(template)
        model.updateColumn(canvas, col, "# Mine\n* mine\n")   // local has content too
        try {
            c.connect(); delay(160)
            assertEquals(SyncState.ChoosingDirection, SyncStateStore.current, "conflict → ask the user")
            assertTrue((model.getCanvasColumnRaw(canvas, col) ?: "").contains("mine"), "nothing synced yet")

            c.chooseInitialSync(SyncController.InitialSyncMode.TO); delay(160)
            assertTrue(storage.files.getValue(colPath).second.contains("mine") &&
                !storage.files.getValue(colPath).second.contains("theirs"),
                "choosing TO replaced the remote with local")
        } finally { SyncStateStore.set(SyncState.NotConfigured, EventBus()) }
    }
}
