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
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.localstorage.SyncConfigDoc
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local deletions must STICK across sync. The structural push's orphan sweep is debounced and can
 * be missed (blocked auth, crash, an error mid-delete) — and a tracked-but-locally-deleted column
 * then looked to the pull like a missing file to IMPORT: the user deleted columns, synced, and
 * watched the remote resurrect them. Reconcile must treat "tracked in state but absent from its
 * canvas's manifest" as the deletion it is, and propagate it to the remote BEFORE pulling.
 */
@OptIn(DelicateCoroutinesApi::class)
class DeletionSyncTest {

    private class RecordingStorage : RemoteStorage {
        val files = mutableMapOf<String, Pair<RemoteFile, String>>()
        val deleted = mutableListOf<String>()
        private var revCounter = 0
        override suspend fun list(folder: String) = RemoteListing(files.values.map { it.first }, rootExists = true)
        override suspend fun download(path: String) = files.getValue(path).let { it.second to it.first }
        override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
            revCounter++
            val f = RemoteFile(path, "rev$revCounter", revCounter.toDouble())
            files[path] = f to content
            return f
        }
        override suspend fun delete(path: String) { files.remove(path); deleted += path }
        override suspend fun revoke() {}
        override suspend fun columnVv(path: String): VersionVector = emptyMap()
    }

    @Test
    fun locallyDeletedColumnIsNotResurrectedByReconcile(): dynamic = GlobalScope.promise {
        val name = "_del-stick"
        val canvas = CanvasName(name)
        val model = Model(canvas)
        val bus = EventBus<Event>()
        val storage = RecordingStorage()
        val root = "/$name"
        model.writeSyncConfig(SyncConfigDoc(
            provider = SyncProvider.DROPBOX, enabled = true, rootFolder = root,
            refreshToken = "test-token", accountEmail = null,
        ))
        model.writeSyncState(SyncStateDoc.EMPTY)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        val col = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, col, "# T\n* doomed\n")
        val c = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
        c.start()
        delay(30)
        bus.fire(ColumnsChanged(canvas, setOf(col)))
        delay(60)   // column + entry established on the remote
        val colPath = SyncPaths.columnPath(root, canvas, col)
        assertTrue(colPath in storage.files, "setup: the column synced up")

        // The user deletes the column; the debounced structural sweep is MISSED (crash/blocked).
        val spec = model.getCanvas(canvas)!!
        model.addCanvas(Canvas(spec.name, spec.parent, spec.columns.filterNot { it == col }, spec.layouts))
        model.deleteColumn(canvas, col)

        try {
            c.reconcile()
            delay(100)

            assertEquals(null, model.getCanvasColumnRaw(canvas, col),
                "the deleted column must NOT be re-imported from the remote")
            assertTrue(colPath !in storage.files,
                "the deletion propagates: the remote file is gone, so other devices converge")
            assertTrue(model.readSyncState().entries[colPath]?.tombstone == true,
                "the deletion is recorded as a tombstone (kept, not dropped) so a concurrent re-add can't resurrect it")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }

    @Test
    fun deletingAWholeCanvasPropagatesToRemote(): dynamic = GlobalScope.promise {
        val name = "_del-canvas"
        val canvas = CanvasName(name)
        val model = Model(canvas)
        val bus = EventBus<Event>()
        val storage = RecordingStorage()
        val root = "/$name"
        model.writeSyncConfig(SyncConfigDoc(
            provider = SyncProvider.DROPBOX, enabled = true, rootFolder = root,
            refreshToken = "test-token", accountEmail = null,
        ))
        model.writeSyncState(SyncStateDoc.EMPTY)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        val col = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, col, "# T\n* x\n")
        val c = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
        c.start()
        delay(30)
        bus.fire(ColumnsChanged(canvas, setOf(col)))
        delay(60)
        val colPath = SyncPaths.columnPath(root, canvas, col)
        val manifestPath = SyncPaths.manifestPath(root, canvas)
        assertTrue(colPath in storage.files, "setup: column synced")
        assertTrue(manifestPath in storage.files, "setup: manifest synced")

        // Delete the ENTIRE canvas locally (manifest gone) — the gap the sweep fix closes.
        model.deleteCanvas(canvas)
        assertEquals(null, model.getCanvas(canvas), "canvas gone locally")

        try {
            c.reconcile()
            delay(100)
            assertTrue(colPath !in storage.files, "column swept from remote")
            assertTrue(manifestPath !in storage.files,
                "the MANIFEST must be swept too — otherwise the pull re-imports it and the board resurrects")
            assertEquals(null, model.getCanvas(canvas),
                "the deleted board must NOT be re-created locally by the reconcile's pull")
            assertEquals(null, model.readSyncState().entries[colPath], "column bookkeeping dropped")
            assertEquals(null, model.readSyncState().entries[manifestPath], "manifest bookkeeping dropped")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }
}
