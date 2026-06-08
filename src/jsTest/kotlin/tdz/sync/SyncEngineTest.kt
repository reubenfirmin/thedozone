package tdz.sync

import tdz.model.localstorage.SyncEntry
import tdz.model.localstorage.SyncStateDoc
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SyncEngineTest {

    // --- conflict resolution (pure) ---

    @Test fun localNewerWins() =
        assertEquals(Winner.LOCAL, resolveConflict(localUpdatedAt = 200.0, remoteServerModified = 100.0))

    @Test fun remoteNewerWins() =
        assertEquals(Winner.REMOTE, resolveConflict(localUpdatedAt = 100.0, remoteServerModified = 200.0))

    @Test fun tieFavoursRemote() =
        assertEquals(Winner.REMOTE, resolveConflict(localUpdatedAt = 100.0, remoteServerModified = 100.0))

    // --- fake storage (rev-aware: honors optimistic ifMatchRev) ---

    private class FakeStorage(initial: List<Pair<RemoteFile, String>>) : RemoteStorage {
        val files = initial.associate { it.first.path to it }.toMutableMap()
        val deleted = mutableListOf<String>()
        private var revCounter = 0

        override suspend fun list(folder: String) = files.values.map { it.first }

        override suspend fun download(path: String) =
            files.getValue(path).let { it.second to it.first }

        override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
            val current = files[path]?.first
            // Optimistic concurrency: a non-null ifMatchRev that no longer matches => conflict.
            if (ifMatchRev != null && current != null && current.rev != ifMatchRev) {
                throw RevConflictException(path)
            }
            revCounter++
            val f = RemoteFile(path, "rev$revCounter", current?.serverModified ?: 0.0)
            files[path] = f to content
            return f
        }

        override suspend fun delete(path: String) { files.remove(path); deleted += path }
        override suspend fun revoke() {}
    }

    private fun engine(storage: RemoteStorage, local: MutableMap<String, String>) =
        SyncEngine(
            storage = storage,
            readLocal = { local[it] },
            writeLocal = { p, c -> local[p] = c },
            deleteLocal = { local.remove(it); Unit },
        )

    // --- push (the core conflict path) ---

    @Test
    fun pushCleanWhenNoKnownRev() = runTest {
        val storage = FakeStorage(emptyList())
        val local = mutableMapOf<String, String>()

        val (state, winner) = engine(storage, local)
            .push("/n/_/col-0.md", "hi", localUpdatedAt = 100.0, state = SyncStateDoc.EMPTY)

        assertNull(winner)                                              // no conflict
        assertNotNull(state.entries["/n/_/col-0.md"]?.rev)             // rev recorded
        assertEquals("hi", storage.files.getValue("/n/_/col-0.md").second)
    }

    @Test
    fun pushConflictLocalNewerForceOverwrites() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rRemote", serverModified = 50.0)
        val storage = FakeStorage(listOf(remote to "remote-content"))
        val local = mutableMapOf("/n/_/col-0.md" to "local-content")
        // Known rev is stale -> upload(ifMatchRev="rOld") conflicts with current "rRemote".
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 200.0))

        val (next, winner) = engine(storage, local)
            .push("/n/_/col-0.md", "local-content", localUpdatedAt = 200.0, state = state)

        assertEquals(Winner.LOCAL, winner)
        assertEquals("local-content", storage.files.getValue("/n/_/col-0.md").second) // remote overwritten
        assertEquals(200.0, next.entries["/n/_/col-0.md"]?.localUpdatedAt)
    }

    @Test
    fun pushConflictRemoteNewerWritesLocal() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rRemote", serverModified = 500.0)
        val storage = FakeStorage(listOf(remote to "remote-content"))
        val local = mutableMapOf("/n/_/col-0.md" to "local-content")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 100.0))

        val (next, winner) = engine(storage, local)
            .push("/n/_/col-0.md", "local-content", localUpdatedAt = 100.0, state = state)

        assertEquals(Winner.REMOTE, winner)
        assertEquals("remote-content", local["/n/_/col-0.md"])           // local overwritten with remote
        assertEquals("rRemote", next.entries["/n/_/col-0.md"]?.rev)
        assertEquals("remote-content", storage.files.getValue("/n/_/col-0.md").second) // remote untouched
    }

    @Test
    fun pushConflictTieFavoursRemote() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rRemote", serverModified = 300.0)
        val storage = FakeStorage(listOf(remote to "remote-content"))
        val local = mutableMapOf("/n/_/col-0.md" to "local-content")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 300.0))

        val (_, winner) = engine(storage, local)
            .push("/n/_/col-0.md", "local-content", localUpdatedAt = 300.0, state = state)

        assertEquals(Winner.REMOTE, winner)
        assertEquals("remote-content", local["/n/_/col-0.md"])
    }

    // --- batch push ---

    @Test
    fun pushBatchUploadsAllAndFoldsState() = runTest {
        val storage = FakeStorage(emptyList())
        val local = mutableMapOf<String, String>()
        val items = listOf(
            PushItem("/n/_/col-0.md", "a", 1.0),
            PushItem("/n/_/col-1.md", "b", 1.0),
            PushItem("/n/_/_canvas.json", "{}", 1.0),
        )
        val (state, conflicts) = engine(storage, local).pushBatch(items, SyncStateDoc.EMPTY)

        assertTrue(conflicts.isEmpty())
        assertEquals(3, state.entries.size)
        assertEquals("a", storage.files.getValue("/n/_/col-0.md").second)
        assertEquals("{}", storage.files.getValue("/n/_/_canvas.json").second)
    }

    @Test
    fun pushBatchSurfacesPerFileConflict() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rRemote", serverModified = 999.0)
        val storage = FakeStorage(listOf(remote to "remote"))
        val local = mutableMapOf("/n/_/col-0.md" to "local")
        // col-0 has a stale known rev (conflict, remote newer -> REMOTE); col-1 is clean.
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 1.0))
        val items = listOf(
            PushItem("/n/_/col-0.md", "local", 1.0),
            PushItem("/n/_/col-1.md", "new", 1.0),
        )
        val (next, conflicts) = engine(storage, local).pushBatch(items, state)

        assertEquals(listOf("/n/_/col-0.md" to Winner.REMOTE), conflicts)
        assertEquals("remote", local["/n/_/col-0.md"])           // remote won, local overwritten
        assertEquals("rRemote", next.entries["/n/_/col-0.md"]?.rev)
        assertEquals("new", storage.files.getValue("/n/_/col-1.md").second)  // clean push went through
    }

    // --- deleteRemote ---

    @Test
    fun deleteRemoteRemovesFileAndEntry() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "r1", serverModified = 1.0)
        val storage = FakeStorage(listOf(remote to "x"))
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("r1", 1.0))

        val next = engine(storage, mutableMapOf()).deleteRemote("/n/_/col-0.md", state)

        assertFalse(next.entries.containsKey("/n/_/col-0.md"))
        assertTrue(storage.deleted.contains("/n/_/col-0.md"))
    }

    @Test
    fun deleteRemoteBatchRemovesAllAndEntries() = runTest {
        val storage = FakeStorage(listOf(
            RemoteFile("/n/_/col-0.md", "r0", 1.0) to "a",
            RemoteFile("/n/_/col-1.md", "r1", 1.0) to "b",
        ))
        val state = SyncStateDoc.EMPTY
            .with("/n/_/col-0.md", SyncEntry("r0", 1.0))
            .with("/n/_/col-1.md", SyncEntry("r1", 1.0))

        val next = engine(storage, mutableMapOf())
            .deleteRemoteBatch(listOf("/n/_/col-0.md", "/n/_/col-1.md"), state)

        assertTrue(next.entries.isEmpty())
        assertTrue(storage.deleted.containsAll(listOf("/n/_/col-0.md", "/n/_/col-1.md")))
    }

    // --- pull / reconcile ---

    @Test
    fun pullImportsUnknownFile() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rA", 10.0)
        val storage = FakeStorage(listOf(remote to "hello"))
        val local = mutableMapOf<String, String>()
        val state = SyncStateDoc.EMPTY   // nothing known locally -> import

        val result = engine(storage, local).pull(root = "/n", state = state)

        assertEquals("hello", local["/n/_/col-0.md"])
        assertEquals("rA", result.state.entries["/n/_/col-0.md"]?.rev)
        assertTrue(result.changedPaths.contains("/n/_/col-0.md"))
    }

    @Test
    fun pullReDownloadsWhenRemoteRevMoved() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rNew", 10.0)
        val storage = FakeStorage(listOf(remote to "updated"))
        val local = mutableMapOf("/n/_/col-0.md" to "old")
        // Tracked, but the remote rev moved on from "rOld".
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 5.0))

        val result = engine(storage, local).pull(root = "/n", state = state)

        assertEquals("updated", local["/n/_/col-0.md"])
        assertEquals("rNew", result.state.entries["/n/_/col-0.md"]?.rev)
        assertTrue(result.changedPaths.contains("/n/_/col-0.md"))
    }

    @Test
    fun pullSkipsWhenRevUnchanged() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rA", 10.0)
        val storage = FakeStorage(listOf(remote to "hello"))
        val local = mutableMapOf("/n/_/col-0.md" to "hello")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rA", 5.0))

        val result = engine(storage, local).pull(root = "/n", state = state)

        assertTrue(result.changedPaths.isEmpty())
    }

    @Test
    fun pullKeepsLocalWhenLocalNewerOnRevDivergence() = runTest {
        // Remote rev moved on, but our local edit is NEWER than the remote's server-modified time.
        val remote = RemoteFile("/n/_/col-0.md", "rNew", serverModified = 50.0)
        val storage = FakeStorage(listOf(remote to "remote-older"))
        val local = mutableMapOf("/n/_/col-0.md" to "local-newer")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", localUpdatedAt = 100.0))

        val result = engine(storage, local).pull(root = "/n", state = state)

        assertEquals("local-newer", local["/n/_/col-0.md"], "local must NOT be clobbered by the older remote")
        assertTrue(result.keptLocalPaths.contains("/n/_/col-0.md"), "kept-local path is reported for push")
        assertFalse(result.changedPaths.contains("/n/_/col-0.md"), "local was not changed by the pull")
    }

    @Test
    fun pullDoesNotDeletePendingLocalCreation() = runTest {
        // A column edited/created locally whose push never succeeded: tracked with rev == null, absent
        // from the remote. Pull must NOT delete it (the reported "blows away local changes" bug).
        val storage = FakeStorage(emptyList())   // remote has nothing
        val local = mutableMapOf("/n/_/col-0.md" to "unpushed local work")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry(rev = null, localUpdatedAt = 100.0))

        val result = engine(storage, local).pull(root = "/n", state = state)

        assertEquals("unpushed local work", local["/n/_/col-0.md"], "pending local file must survive pull")
        assertTrue(result.state.entries.containsKey("/n/_/col-0.md"), "its bookkeeping is retained")
    }

    @Test
    fun pullDeletesLocallyWhenRemovedRemotely() = runTest {
        val storage = FakeStorage(emptyList())   // remote no longer has the file
        val local = mutableMapOf("/n/_/col-0.md" to "stale")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rA", 5.0))

        val result = engine(storage, local).pull(root = "/n", state = state)

        assertFalse(local.containsKey("/n/_/col-0.md"))
        assertFalse(result.state.entries.containsKey("/n/_/col-0.md"))
        assertTrue(result.changedPaths.contains("/n/_/col-0.md"))
    }
}
