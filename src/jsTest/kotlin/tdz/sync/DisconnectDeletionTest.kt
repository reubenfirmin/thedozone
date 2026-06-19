package tdz.sync

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import tdz.controller.events.Event
import tdz.controller.events.RefreshCanvas
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Model
import tdz.model.Split
import tdz.model.localstorage.SyncConfigDoc
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With sync ON, disconnect; delete columns while disconnected; reconnect choosing **Sync TO** ("this
 * device is correct") — the deleted columns must NOT come back, and the remote is made to mirror the
 * local board (the extra remote columns are deleted). TO asserts local and does not merge.
 */
@OptIn(DelicateCoroutinesApi::class)
class DisconnectDeletionTest {

    private class RecordingStorage : RemoteStorage {
        val files = mutableMapOf<String, Pair<RemoteFile, String>>()
        private var rev = 0
        override suspend fun list(folder: String) = RemoteListing(files.values.map { it.first }, rootExists = true)
        override suspend fun download(path: String) = files.getValue(path).let { it.second to it.first }
        override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
            rev++; val f = RemoteFile(path, "r$rev", rev.toDouble()); files[path] = f to content; return f
        }
        override suspend fun delete(path: String) { files.remove(path) }
        override suspend fun revoke() {}
        override suspend fun columnVv(path: String): VersionVector = emptyMap()
    }

    @Test
    fun syncToAfterDisconnectedDeletePropagatesAndDoesNotResurrect(): dynamic = GlobalScope.promise {
        val name = "_disco"
        val canvas = CanvasName(name)
        val model = Model(canvas)
        val bus = EventBus<Event>()
        val storage = RecordingStorage()
        val root = "/$name"
        model.writeSyncConfig(SyncConfigDoc(SyncProvider.DROPBOX, enabled = false, rootFolder = root, refreshToken = null, accountEmail = null))
        model.writeSyncState(SyncStateDoc.EMPTY)
        // A 4-column board.
        fun board(cols: List<Int>) = Canvas(canvas, null, cols.map { ColumnId(it.toString()) },
            listOf(ColumnLayoutSpec(ColumnId("0"), Split.VERTICAL, cols.drop(1).map { ColumnId(it.toString()) })))
        model.addCanvas(board(listOf(0, 1, 2, 3, 4)))
        for (col in 1..4) model.updateColumn(canvas, ColumnId(col.toString()), "# C$col\n* x\n")

        val c = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
        c.connectOverride = { ConnectResult(accountEmail = "rf@test", refreshToken = "token") }
        try {
            c.connect(); delay(160)   // initial sync pushes all 4 columns + manifest
            assertTrue(storage.files.containsKey(SyncPaths.columnPath(root, canvas, ColumnId("4"))), "setup: column 4 synced")

            c.disconnect(); delay(40)

            // Delete columns 3 and 4 while disconnected.
            model.deleteColumn(canvas, ColumnId("3"))
            model.deleteColumn(canvas, ColumnId("4"))
            model.addCanvas(board(listOf(0, 1, 2)))
            model.changeCanvas(canvas)
            bus.fire(RefreshCanvas(canvas, structural = true))
            delay(40)

            c.connect(SyncController.InitialSyncMode.TO); delay(220)   // reconnect, asserting local

            assertEquals(listOf(0, 1, 2).map { ColumnId(it.toString()) }, model.getCanvas(canvas)!!.columns,
                "deleted columns must NOT return on reconnect")
            assertTrue(!storage.files.containsKey(SyncPaths.columnPath(root, canvas, ColumnId("3"))) &&
                !storage.files.containsKey(SyncPaths.columnPath(root, canvas, ColumnId("4"))),
                "the deletions propagated to the remote")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }

    @Test
    fun syncToMirrorsLocalDeletingUnrelatedRemoteFiles(): dynamic = GlobalScope.promise {
        val name = "_to-mirror"
        val canvas = CanvasName(name)
        val model = Model(canvas)
        val bus = EventBus<Event>()
        val storage = RecordingStorage()
        val root = "/$name"
        model.writeSyncConfig(SyncConfigDoc(SyncProvider.DROPBOX, enabled = false, rootFolder = root, refreshToken = null, accountEmail = null))
        model.writeSyncState(SyncStateDoc.EMPTY)
        model.addCanvas(Canvas(canvas, null, listOf(ColumnId("0"), ColumnId("1")),
            listOf(ColumnLayoutSpec(ColumnId("0"), Split.VERTICAL, listOf(ColumnId("1"))))))
        model.updateColumn(canvas, ColumnId("1"), "# Mine\n* keep\n")
        // The remote already holds a DIFFERENT board the user doesn't have locally.
        val strayPath = "$root/ghost-board/col-1.md"
        storage.files[strayPath] = RemoteFile(strayPath, "rGhost", 1.0) to "# Ghost\n* stale\n"
        val c = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
        c.connectOverride = { ConnectResult(accountEmail = "rf@test", refreshToken = "token") }
        try {
            c.connect(SyncController.InitialSyncMode.TO); delay(180)
            assertTrue(!storage.files.containsKey(strayPath),
                "Sync TO mirrors local — a remote file with no local counterpart is deleted")
            assertTrue(storage.files.containsKey(SyncPaths.columnPath(root, canvas, ColumnId("1"))),
                "the local board is pushed up")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }
}
