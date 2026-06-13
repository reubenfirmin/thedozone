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
import tdz.model.localstorage.SyncConfigDoc
import tdz.model.localstorage.SyncEntry
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When sync can't authenticate without an interactive prompt (e.g. an expired Drive token on
 * startup), the automatic reconcile must do NO remote work — not even the orphan-deletion sweep,
 * which otherwise fires a (startup-blocked) auth popup per file and aborts. It bails quietly to
 * NeedsReconnect; the pending local state (including deletions) stays recorded for the next
 * authed reconcile.
 */
@OptIn(DelicateCoroutinesApi::class)
class SyncReadinessTest {

    private class NotReadyStorage : RemoteStorage {
        var listCalls = 0
        var deleteCalls = 0
        override fun isReady() = false
        override suspend fun list(folder: String): RemoteListing { listCalls++; return RemoteListing(emptyList(), true) }
        override suspend fun download(path: String) = "" to RemoteFile(path, "r", 1.0)
        override suspend fun upload(path: String, content: String, ifMatchRev: String?) = RemoteFile(path, "r", 1.0)
        override suspend fun delete(path: String) { deleteCalls++ }
        override suspend fun revoke() {}
        override suspend fun columnVv(path: String): VersionVector = emptyMap()
    }

    @Test
    fun reconcileDoesNoRemoteWorkAndDoesNotPopWhenNotReady(): dynamic = GlobalScope.promise {
        val name = "_rdy"
        val canvas = CanvasName(name)
        val model = Model(canvas)
        val bus = EventBus<Event>()
        model.writeSyncConfig(SyncConfigDoc(
            provider = SyncProvider.GOOGLE_DRIVE, enabled = true, rootFolder = "/$name",
            refreshToken = null, accountEmail = "rf@test",
        ))
        model.writeSyncState(SyncStateDoc.EMPTY)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        // A locally-deleted column tracked in state (the orphan the sweep would try to delete remotely).
        val orphan = SyncPaths.columnPath("/$name", canvas, ColumnId(99))
        model.writeSyncState(SyncStateDoc.EMPTY.with(orphan, SyncEntry("rev1", 1.0)))
        val storage = NotReadyStorage()
        val c = SyncController(model, bus, storageOverride = storage)
        try {
            c.start()   // builds engine + reconciles
            delay(50)

            assertEquals(0, storage.listCalls, "no pull when not authed")
            assertEquals(0, storage.deleteCalls, "no orphan-delete sweep when not authed — that's what fired the popup storm")
            assertEquals(SyncState.NeedsReconnect, SyncStateStore.current, "bails quietly to NeedsReconnect")
            assertTrue(model.readSyncState().entries.containsKey(orphan),
                "the pending deletion stays recorded for the next authed reconcile")
        } finally {
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }
}
