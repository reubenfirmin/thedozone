package tdz.sync

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.runTest
import tdz.controller.events.Event
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.model.localstorage.SyncConfigDoc
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Sync instrumentation: every remote op is timed and tallied so a slow sync says WHERE the time
 * went (`14× columnVv (8200ms), …`) instead of just "Syncing…".
 */
@OptIn(DelicateCoroutinesApi::class)
class SyncLogTest {

    private class NoopStorage : RemoteStorage {
        override suspend fun list(folder: String) = RemoteListing(emptyList(), rootExists = true)
        override suspend fun download(path: String) = "content" to RemoteFile(path, "r1", 1.0)
        override suspend fun upload(path: String, content: String, ifMatchRev: String?) = RemoteFile(path, "r2", 2.0)
        override suspend fun delete(path: String) {}
        override suspend fun revoke() {}
        override suspend fun columnVv(path: String): VersionVector = emptyMap()
    }

    @Test
    fun storageOpsAreTimedLoggedAndTallied() = runTest {
        val lines = mutableListOf<String>()
        val prev = SyncLog.sink
        SyncLog.sink = { lines += it }
        SyncLog.resetCounters()
        try {
            val s = InstrumentedStorage(NoopStorage())
            s.upload("/n/_/col-1.md", "hi", ifMatchRev = null)
            s.download("/n/_/col-1.md")
            s.download("/n/_/_canvas.json")
            s.list("/n")

            assertTrue(lines.any { it.contains("upload") && it.contains("/n/_/col-1.md") && it.contains("ms)") },
                "each op logs its path and duration; got: $lines")
            val summary = SyncLog.summary()
            assertTrue(summary.contains("1× upload") && summary.contains("2× download") && summary.contains("1× list"),
                "the summary tallies ops for the window; got: '$summary'")
        } finally {
            SyncLog.sink = prev
            SyncLog.resetCounters()
        }
    }

    @Test
    fun failuresAreLoggedWithDurationAndRethrown() = runTest {
        val lines = mutableListOf<String>()
        val prev = SyncLog.sink
        SyncLog.sink = { lines += it }
        SyncLog.resetCounters()
        try {
            val boom = object : RemoteStorage by NoopStorage() {
                override suspend fun download(path: String): Pair<String, RemoteFile> = throw Exception("boom")
            }
            val s = InstrumentedStorage(boom)
            val thrown = runCatching { s.download("/n/_/col-1.md") }.exceptionOrNull()
            assertTrue(thrown?.message == "boom", "the failure propagates")
            assertTrue(lines.any { it.contains("FAILED") && it.contains("boom") },
                "and is logged with its cause; got: $lines")
        } finally {
            SyncLog.sink = prev
            SyncLog.resetCounters()
        }
    }

    @Test
    fun reconcileNarratesItsPhasesAndEndsWithASummary(): dynamic = GlobalScope.promise {
        val name = "_slog-rec"
        val canvas = CanvasName(name)
        val model = Model(canvas)
        val bus = EventBus<Event>()
        model.writeSyncConfig(SyncConfigDoc(
            provider = SyncProvider.DROPBOX, enabled = true, rootFolder = "/$name",
            refreshToken = "test-token", accountEmail = null,
        ))
        model.writeSyncState(SyncStateDoc.EMPTY)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        val lines = mutableListOf<String>()
        val prev = SyncLog.sink
        SyncLog.sink = { lines += it }
        try {
            val c = SyncController(model, bus, storageOverride = NoopStorage())
            c.start()   // start() reconciles
            delay(60)

            assertTrue(lines.any { it.contains("reconcile started") }, "got: $lines")
            assertTrue(lines.any { it.contains("pull:") && it.contains("ms)") }, "pull phase timed; got: $lines")
            assertTrue(lines.any { it.contains("reconcile finished in") && it.contains("×") },
                "the closing line carries the per-op tally; got: $lines")
        } finally {
            SyncLog.sink = prev
            SyncLog.resetCounters()
            SyncStateStore.set(SyncState.NotConfigured, bus)
        }
    }
}
