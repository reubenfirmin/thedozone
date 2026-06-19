package tdz.sync

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import tdz.controller.Uploader
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.Event
import tdz.controller.io.ImportMode
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.localstorage.SyncConfigDoc
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Uploading a `.tdz` (e.g. restoring a board from a backup) must be visible to sync: the import
 * writes column content straight into the model, which fires no events on its own, so the
 * [Uploader] has to stamp the touched columns as a local change. Without it the upload is invisible
 * to sync — a stale remote silently reverts it on the next reconcile, and an offline import never
 * pushes up. This mirrors the contract [SyncController.restoreAutoBackup] already honours.
 */
@OptIn(DelicateCoroutinesApi::class)
class ImportSyncTest {

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

    private fun setup(name: String): Triple<Model, EventBus<Event>, RecordingStorage> {
        val model = Model(CanvasName(name))
        val bus = EventBus<Event>()
        val storage = RecordingStorage()
        model.writeSyncConfig(SyncConfigDoc(
            provider = SyncProvider.DROPBOX, enabled = true, rootFolder = "/$name",
            refreshToken = "test-token", accountEmail = null,
        ))
        model.writeSyncState(SyncStateDoc.EMPTY)
        return Triple(model, bus, storage)
    }

    @Test
    fun offlineImportMarksColumnPendingAndBumpsVv(): dynamic = GlobalScope.promise {
        val name = "_imp-pending"
        val canvas = CanvasName(name)
        val (model, bus, storage) = setup(name)
        val root = "/$name"
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        val col = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, col, "# T\n* original\n")
        val c = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
        c.start()
        delay(40)
        bus.fire(ColumnsChanged(canvas, setOf(col)))   // push column 1 up: establish rev + vv
        delay(60)
        val colPath = SyncPaths.columnPath(root, canvas, col)
        assertNotNull(model.readSyncState().entries[colPath]?.rev, "setup: column synced with a rev")
        val vvBefore = model.readSyncState().entries[colPath]?.vv?.get(DeviceId.get()) ?: 0L
        storage.uploads.clear()
        try {
            // Sync drops offline; the user restores the board by uploading a backup.
            SyncStateStore.set(SyncState.Offline, bus)
            Uploader(model, bus).applyImport(name, "# T\n* restored\n", ImportMode.HERE)
            delay(40)

            val entry = model.readSyncState().entries[colPath]
            assertNotNull(entry, "the imported column stays tracked")
            assertNull(entry.rev, "offline import marks the column pending so the next reconcile pushes it")
            assertTrue((entry.vv[DeviceId.get()] ?: 0L) > vvBefore,
                "the import bumps the column's version vector — it is a real local change")
            assertTrue(storage.uploads.isEmpty(), "but nothing is pushed while offline")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }

    @Test
    fun importedContentPushesUpAndIsNotRevertedByStaleRemote(): dynamic = GlobalScope.promise {
        val name = "_imp-stick"
        val canvas = CanvasName(name)
        val (model, bus, storage) = setup(name)
        val root = "/$name"
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        val col = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, col, "# T\n* original\n")
        val c = SyncController(model, bus, storageOverride = storage).also { it.pushDebounceMs = 5 }
        c.start()
        delay(40)
        bus.fire(ColumnsChanged(canvas, setOf(col)))   // establish a synced baseline
        delay(60)
        val colPath = SyncPaths.columnPath(root, canvas, col)
        // The remote now holds the baseline ("original"); the import will replace it locally. Record
        // the remote's vv so the post-import comparison is a genuine causal contest.
        storage.remoteVv[colPath] = model.readSyncState().entries[colPath]?.vv ?: emptyMap()
        storage.uploads.clear()
        try {
            SyncStateStore.set(SyncState.Offline, bus)
            Uploader(model, bus).applyImport(name, "# T\n* restored\n", ImportMode.HERE)
            delay(40)
            val localAfterImport = model.getCanvasColumnRaw(canvas, col)!!
            assertTrue(localAfterImport.contains("restored"), "setup: the import landed locally")

            // Back online: a plain reconcile must push the restored content up, not pull the stale
            // remote ("original") over it.
            SyncStateStore.set(SyncState.Synced, bus)
            c.reconcile()
            delay(120)

            assertTrue(colPath in storage.uploads, "the restored content is pushed up to the remote")
            assertTrue(storage.files.getValue(colPath).second.contains("restored"),
                "the remote converges on the locally-imported content")
            assertTrue(model.getCanvasColumnRaw(canvas, col)!!.contains("restored"),
                "the import survives the reconcile (not reverted to the stale remote)")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }
}
