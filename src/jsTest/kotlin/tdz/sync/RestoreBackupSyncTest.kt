package tdz.sync

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.Event
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The restore-under-sync contract: restoring the periodic backup must STICK. The remote holds the
 * newer-but-unwanted content (the bad edit was already pushed), so a naive restore — which also
 * rolls back the sync bookkeeping — makes the remote look causally ahead, and the very next pull
 * undoes the rollback. The restore must instead keep the live bookkeeping, re-stamp everything
 * local as pending/newest, and push the restored content UP.
 */
@OptIn(DelicateCoroutinesApi::class)
class RestoreBackupSyncTest {

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

    /** Restore is scoped to the CURRENT board: other canvases keep their latest content. */
    @Test
    fun restoreLeavesOtherCanvasesAlone(): dynamic = GlobalScope.promise {
        val name = "_rb-scope"
        val current = CanvasName(name)
        val other = CanvasName("_rb-scope-other")
        val model = Model(current)
        val bus = EventBus<Event>()
        model.writeSyncConfig(SyncConfigDoc(
            provider = SyncProvider.DROPBOX, enabled = false, rootFolder = "/$name",
            refreshToken = null, accountEmail = null,
        ))
        model.writeSyncState(SyncStateDoc.EMPTY)
        model.addCanvas(canvasFromTemplate(current, null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(other, current, SINGLE_PANE))
        val curCol = model.getCanvas(current)!!.firstNonLayout()
        val otherCol = model.getCanvas(other)!!.firstNonLayout()
        model.updateColumn(current, curCol, "# Mine\n* old\n")
        model.updateColumn(other, otherCol, "# Other\n* old\n")
        assertTrue(LocalBackup.autoSnapshot())

        model.updateColumn(current, curCol, "# Mine\n* new\n")
        model.updateColumn(other, otherCol, "# Other\n* new\n")
        val c = SyncController(model, bus, storageOverride = RecordingStorage())
        try {
            assertTrue(c.restoreAutoBackup())
            delay(50)

            assertTrue(model.getCanvasColumnRaw(current, curCol)!!.contains("* old"),
                "the current board rolled back")
            assertTrue(model.getCanvasColumnRaw(other, otherCol)!!.contains("* new"),
                "OTHER boards keep their latest content — restore must be per-canvas")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }

    /** A column created after the backup is rolled away AND deleted remotely — never re-imported. */
    @Test
    fun columnsCreatedAfterTheBackupDieRemotelyInsteadOfReimporting(): dynamic = GlobalScope.promise {
        val name = "_rb-newcol"
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
        model.updateColumn(canvas, ColumnId("1"), "# T\n* original\n")
        val c = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
        c.start()
        delay(30)
        bus.fire(ColumnsChanged(canvas, setOf(ColumnId("1"))))
        delay(60)
        assertTrue(LocalBackup.autoSnapshot(), "setup: backup taken BEFORE the new column")

        // A new column arrives after the backup and syncs up.
        val spec = model.getCanvas(canvas)!!
        val newCol = ColumnId.random(spec.columns.toSet())
        model.addCanvas(spec.copy(columns = spec.columns + newCol))
        model.updateColumn(canvas, newCol, "# New\n* post-backup\n")
        bus.fire(ColumnsChanged(canvas, setOf(newCol)))
        delay(60)
        val newColPath = SyncPaths.columnPath(root, canvas, newCol)
        assertTrue(newColPath in storage.uploads, "setup: the post-backup column synced")

        try {
            assertTrue(c.restoreAutoBackup())
            delay(150)
            c.reconcile()   // the pull that used to re-import it
            delay(100)

            assertEquals(null, model.getCanvasColumnRaw(canvas, newCol),
                "the rolled-away column must NOT be re-imported by sync")
            assertTrue(model.getCanvas(canvas)!!.columns.none { it == newCol },
                "the restored manifest doesn't list it")
            assertTrue(newColPath !in storage.files,
                "it is DELETED on the remote, so other devices converge on the rollback")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }

    @Test
    fun restoredDataSurvivesSyncAndPushesUp(): dynamic = GlobalScope.promise {
        val name = "_rb-stick"
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
        model.updateColumn(canvas, col, "# T\n* keep me\n* second item\n")
        val colPath = SyncPaths.columnPath(root, canvas, col)
        val c = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
        c.start()
        delay(30)

        // Good state reaches the remote, then the backup captures it.
        bus.fire(ColumnsChanged(canvas, setOf(col)))
        delay(60)
        assertTrue(colPath in storage.uploads, "setup: good content pushed")
        assertTrue(LocalBackup.autoSnapshot(), "setup: periodic backup taken")

        // The destructive edit (e.g. a bad Clear Done) lands locally AND pushes to the remote.
        model.updateColumn(canvas, col, "# T\n")
        bus.fire(ColumnsChanged(canvas, setOf(col)))
        delay(60)
        assertTrue(!storage.files.getValue(colPath).second.contains("keep me"),
            "setup: the remote now holds the destroyed content")
        storage.remoteVv[colPath] = model.readSyncState().entries[colPath]?.vv ?: emptyMap()
        storage.uploads.clear()

        try {
            assertTrue(c.restoreAutoBackup(), "restore applies")
            delay(120)

            val local = model.getCanvasColumnRaw(canvas, col)
            assertNotNull(local)
            assertTrue(local.contains("keep me"), "local data rolled back to the backup")
            assertTrue(colPath in storage.uploads, "restored content pushed UP to the remote")
            assertTrue(storage.files.getValue(colPath).second.contains("keep me"),
                "the remote converges on the restored content")
            assertEquals(SyncState.Synced, SyncStateStore.current, "restore ends in a healthy state")

            // The acid test: ANOTHER reconcile must not 'correct' the rollback away.
            c.reconcile()
            delay(100)
            assertTrue(model.getCanvasColumnRaw(canvas, col)!!.contains("keep me"),
                "the rollback STICKS across subsequent syncs")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }
}
