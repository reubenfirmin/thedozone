package tdz.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.localstorage.CanvasDoc
import tdz.model.localstorage.SyncEntry
import tdz.model.localstorage.SyncConfigDoc
import tdz.model.localstorage.SyncStateDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The structural-change sync contract: layout mutations (delete/split/import/wormhole target) push
 * the canvas manifest durably — marked pending synchronously, debounced per canvas, never dropped
 * because a reconcile happens to be in flight — while PULLED manifests never echo back out.
 */
@OptIn(DelicateCoroutinesApi::class)
class SyncStructuralTest {

    /** Minimal in-memory remote; [listGate] lets a test hold a reconcile in flight. */
    private class GatedStorage : RemoteStorage {
        val files = mutableMapOf<String, Pair<RemoteFile, String>>()
        val uploads = mutableListOf<String>()
        var listGate: CompletableDeferred<Unit>? = null
        private var revCounter = 0
        override suspend fun list(folder: String): RemoteListing {
            listGate?.await()
            return RemoteListing(files.values.map { it.first }, rootExists = true)
        }
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
        override suspend fun columnVv(path: String): VersionVector = emptyMap()
    }

    private class Setup(name: String) {
        val model = Model(CanvasName(name))
        val bus = EventBus<Event>()
        val storage = GatedStorage()
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
    fun structuralEventMarksTheManifestPendingSynchronouslyThenPushes(): dynamic = GlobalScope.promise {
        val t = Setup("_ss-pend")
        val canvas = CanvasName("_ss-pend")
        t.model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        t.controller().start()
        delay(30)   // let the startup reconcile settle
        t.storage.uploads.clear()

        t.bus.fire(RefreshCanvas(canvas, structural = true))

        val path = SyncPaths.manifestPath(t.root, canvas)
        assertNotNull(t.model.readSyncState().entries[path], "manifest entry exists immediately")
        assertNull(t.model.readSyncState().entries[path]?.rev,
            "manifest marked pending SYNCHRONOUSLY — crash/offline-safe before the debounce fires")
        delay(60)
        assertTrue(path in t.storage.uploads, "the debounced push uploaded the manifest")
        assertNotNull(t.model.readSyncState().entries[path]?.rev, "push recorded the rev (pending cleared)")
    }

    @Test
    fun structuralChangeDuringAReconcileIsNotDropped(): dynamic = GlobalScope.promise {
        val t = Setup("_ss-during")
        val canvas = CanvasName("_ss-during")
        t.model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        val gate = CompletableDeferred<Unit>()
        t.storage.listGate = gate
        t.controller().start()   // reconcile launches and parks on list()

        t.bus.fire(RefreshCanvas(canvas, structural = true))   // e.g. user deletes a column mid-pull
        gate.complete(Unit)
        delay(100)

        val path = SyncPaths.manifestPath(t.root, canvas)
        assertTrue(path in t.storage.uploads,
            "a structural change made WHILE a reconcile is in flight must still reach the remote")
    }

    @Test
    fun pulledManifestDoesNotEchoBackOut(): dynamic = GlobalScope.promise {
        val t = Setup("_ss-echo")
        val remoteCanvas = CanvasName("_ss-echo-x")
        val path = SyncPaths.manifestPath(t.root, remoteCanvas)
        val content = CanvasDoc(remoteCanvas, null,
            canvasFromTemplate(remoteCanvas, null, SINGLE_PANE).columns,
            canvasFromTemplate(remoteCanvas, null, SINGLE_PANE).layouts).encode()
        t.storage.files[path] = RemoteFile(path, "r1", 1.0) to content
        t.controller().start()   // reconcile pulls the manifest -> writeLocal -> RefreshCanvas
        delay(100)

        assertFalse(path in t.storage.uploads, "a manifest the sync itself just pulled must not be pushed back")
        assertNotNull(t.model.getCanvas(remoteCanvas), "the pulled canvas was imported locally")
    }

    @Test
    fun structuralBurstCoalescesIntoOnePush(): dynamic = GlobalScope.promise {
        val t = Setup("_ss-burst")
        val canvas = CanvasName("_ss-burst")
        t.model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        t.controller().start()
        delay(30)
        t.storage.uploads.clear()

        repeat(3) { t.bus.fire(RefreshCanvas(canvas, structural = true)) }   // split + delete + undo
        delay(60)

        val path = SyncPaths.manifestPath(t.root, canvas)
        assertEquals(1, t.storage.uploads.count { it == path },
            "a burst of structural events coalesces into one debounced manifest push")
    }

    @Test
    fun structuralPushOfParentDoesNotDeleteChildBoardColumns(): dynamic = GlobalScope.promise {
        // Regression: the per-canvas orphan sweep scoped by folder PREFIX, so a structural push of
        // parent "a" (folder /_oss/a) also matched child "a/b"'s columns (/_oss/a/b/col-*.md) and
        // batch-deleted them from the remote — silent child data loss. The sweep must match only the
        // pushed board's OWN columns.
        val t = Setup("_oss")
        val a = CanvasName("a")
        val ab = CanvasName("a/b")
        t.model.addCanvas(canvasFromTemplate(a, a.parentName(), SINGLE_PANE))
        t.model.addCanvas(canvasFromTemplate(ab, ab.parentName(), SINGLE_PANE))
        t.controller().start()
        delay(30)   // let the startup reconcile settle before seeding

        // The child's column file is a known synced artifact (present on the remote and in state).
        val childCol = SyncPaths.columnPath(t.root, ab, ColumnId(1))
        t.storage.files[childCol] = RemoteFile(childCol, "r1", 1.0) to "# child content\n"
        t.model.writeSyncState(t.model.readSyncState().with(childCol, SyncEntry(rev = "r1", localUpdatedAt = 1.0)))

        t.bus.fire(RefreshCanvas(a, structural = true))   // structural change to the PARENT
        delay(60)

        assertTrue(t.storage.files.containsKey(childCol),
            "a structural push of parent 'a' must NOT delete child 'a/b' column files")
    }
}
