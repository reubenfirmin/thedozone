package tdz.sync

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.Event
import tdz.controller.events.RefreshCanvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.localstorage.SyncConfigDoc
import tdz.model.localstorage.SyncEntry
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lapsed-auth contract. While auth is lapsed (NeedsReconnect — e.g. Google's GIS session
 * expired) local edits must be RECORDED but never trigger a network push: with Drive every push
 * attempt mints a token, and a failed silent mint surfaces the Google auth popup — one popup per
 * checkbox click. And the Reconnect that follows must PRESERVE the sync bookkeeping: wiping it
 * (what a fresh connect does) discards the version vectors of the offline edits, so the post-auth
 * pull judged the remote causally ahead and silently reverted them.
 */
@OptIn(DelicateCoroutinesApi::class)
class SyncReconnectTest {

    /** Minimal in-memory remote that records uploads and serves per-column version vectors. */
    private class RecordingStorage : RemoteStorage {
        val files = mutableMapOf<String, Pair<RemoteFile, String>>()
        val uploads = mutableListOf<String>()
        val remoteVv = mutableMapOf<String, VersionVector>()
        private var revCounter = 0
        override suspend fun list(folder: String) = RemoteListing(files.values.map { it.first }, rootExists = true)
        override suspend fun download(path: String) = files.getValue(path).let { it.second to it.first }
        override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
            uploads += path
            revCounter++
            val f = RemoteFile(path, "rev$revCounter", revCounter.toDouble())
            files[path] = f to content
            return f
        }
        override suspend fun delete(path: String) { files.remove(path) }
        override suspend fun revoke() {}
        override suspend fun columnVv(path: String): VersionVector = remoteVv[path] ?: emptyMap()
    }

    private class Setup(name: String) {
        val model = Model(CanvasName(name))
        val bus = EventBus<Event>()
        val storage = RecordingStorage()
        val root = "/$name"
        init {
            model.writeSyncConfig(SyncConfigDoc(
                provider = SyncProvider.DROPBOX, enabled = true, rootFolder = root,
                refreshToken = "test-token", accountEmail = null,
            ))
            model.writeSyncState(SyncStateDoc.EMPTY)   // isolate from other tests (singleton key)
        }
        fun controller() = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
    }

    @Test
    fun editsWhileAuthIsLapsedAreRecordedButNotPushed(): dynamic = GlobalScope.promise {
        val t = Setup("_sr-block")
        val canvas = CanvasName("_sr-block")
        t.model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        t.model.updateColumn(canvas, ColumnId(1), "# T\n* a\n")
        val c = t.controller()
        c.start()
        delay(30)   // let the startup reconcile settle
        t.storage.uploads.clear()
        try {
            SyncStateStore.set(SyncState.NeedsReconnect, t.bus)

            t.bus.fire(ColumnsChanged(canvas, setOf(ColumnId(1))))
            delay(60)

            assertTrue(t.storage.uploads.isEmpty(),
                "NO network push while auth is lapsed — with Drive each attempt pops the auth dialog")
            val path = SyncPaths.columnPath(t.root, canvas, ColumnId(1))
            val entry = t.model.readSyncState().entries[path]
            assertNotNull(entry, "the edit is still RECORDED while blocked")
            assertNull(entry.rev, "recorded as pending so the post-reconnect reconcile pushes it")
            assertTrue((entry.vv[DeviceId.get()] ?: 0L) > 0L, "the edit's version vector is stamped")

            // After auth is restored a plain reconcile must deliver the blocked edit.
            c.reconcile()
            delay(100)
            assertTrue(path in t.storage.uploads, "the pending edit reaches the remote on the next reconcile")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, t.bus)
        }
    }

    @Test
    fun structuralChangesWhileAuthIsLapsedAreRecordedButNotPushed(): dynamic = GlobalScope.promise {
        val t = Setup("_sr-struct")
        val canvas = CanvasName("_sr-struct")
        t.model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        val c = t.controller()
        c.start()
        delay(30)
        t.storage.uploads.clear()
        try {
            SyncStateStore.set(SyncState.NeedsReconnect, t.bus)

            t.bus.fire(RefreshCanvas(canvas, structural = true))
            delay(60)

            assertTrue(t.storage.uploads.isEmpty(), "no manifest push while auth is lapsed")
            val path = SyncPaths.manifestPath(t.root, canvas)
            assertNull(t.model.readSyncState().entries[path]?.rev,
                "manifest still marked pending so the next reconcile retries it")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, t.bus)
        }
    }

    @Test
    fun reconnectKeepsBookkeepingSoOfflineEditsSurviveAndPush(): dynamic = GlobalScope.promise {
        val t = Setup("_sr-keep")
        val canvas = CanvasName("_sr-keep")
        val self = DeviceId.get()
        t.model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        // Local column carries the offline edit (a checked task); the remote still has the stale,
        // causally-OLDER content from before auth lapsed ({self:2} on the remote vs {self:3} local).
        t.model.updateColumn(canvas, ColumnId(1), "# T\n* /checked offline\n")
        val colPath = SyncPaths.columnPath(t.root, canvas, ColumnId(1))
        t.storage.files[colPath] = RemoteFile(colPath, "rStale", 100.0) to "# T\n* checked offline\n"
        t.storage.remoteVv[colPath] = mapOf(self to 2L)
        t.model.writeSyncState(SyncStateDoc.EMPTY.with(colPath,
            SyncEntry(rev = null, localUpdatedAt = 200.0, base = "# T\n* checked offline\n", vv = mapOf(self to 3L))))
        val c = t.controller()
        c.connectOverride = { ConnectResult(accountEmail = "rf@test", refreshToken = "test-token") }
        try {
            SyncStateStore.set(SyncState.NeedsReconnect, t.bus)

            c.reconnect()
            delay(100)

            assertTrue(t.model.getCanvasColumnRaw(canvas, ColumnId(1))!!.contains("* /checked offline"),
                "the edit made while auth was lapsed must SURVIVE the post-reconnect pull")
            assertTrue(colPath in t.storage.uploads, "and it is pushed up, not just kept")
            assertTrue(t.storage.files.getValue(colPath).second.contains("* /checked offline"),
                "the remote converges on the local (causally newer) content")
            assertEquals(SyncState.Synced, SyncStateStore.current, "reconnect ends in a healthy state")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, t.bus)
        }
    }
}
