package tdz.sync

import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Split
import tdz.model.localstorage.CanvasDoc
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

    private class FakeStorage(
        initial: List<Pair<RemoteFile, String>>,
        private val rootExists: Boolean = true,
        remoteVv: Map<String, VersionVector> = emptyMap(),
    ) : RemoteStorage {
        val files = initial.associate { it.first.path to it }.toMutableMap()
        val deleted = mutableListOf<String>()
        private var revCounter = 0
        // Mutable copy of the seed so a test can simulate the controller writing the manifest vv after
        // each push (the production data flow: column vv travels in _canvas.json). Existing call sites
        // pass an immutable map; copying here keeps them all compiling unchanged.
        private val remoteVv: MutableMap<String, VersionVector> = remoteVv.toMutableMap()

        /** Simulate the controller writing a column's version vector into the canvas manifest. */
        fun putRemoteVv(path: String, vv: VersionVector) { remoteVv[path] = vv }

        override suspend fun list(folder: String) = RemoteListing(files.values.map { it.first }, rootExists)

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
        override suspend fun columnVv(path: String): VersionVector = remoteVv[path] ?: emptyMap()
    }

    private fun engine(storage: RemoteStorage, local: MutableMap<String, String>) =
        SyncEngine(
            storage = storage,
            readLocal = { local[it] },
            writeLocal = { p, c -> local[p] = c },
            deleteLocal = { local.remove(it) },
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
    fun pushColumnConflictLocalVvDominatesForceUploads() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rRemote", serverModified = 50.0)
        val storage = FakeStorage(listOf(remote to "REMOTE\n"), remoteVv = mapOf("/n/_/col-0.md" to mapOf("me" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "LOCAL\n")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 200.0, base = "shared\n", vv = mapOf("me" to 2L)))
        val (next, winner) = engine(storage, local).push("/n/_/col-0.md", "LOCAL\n", localUpdatedAt = 200.0, state = state, self = "me")
        assertNull(winner)
        assertEquals("LOCAL\n", storage.files.getValue("/n/_/col-0.md").second)
        assertEquals(mapOf("me" to 2L), next.entries["/n/_/col-0.md"]?.vv)
    }

    @Test
    fun pushColumnConflictRemoteVvDominatesTakesRemote() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rRemote", serverModified = 500.0)
        val storage = FakeStorage(listOf(remote to "REMOTE\n"), remoteVv = mapOf("/n/_/col-0.md" to mapOf("me" to 1L, "other" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "LOCAL\n")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 100.0, base = "shared\n", vv = mapOf("me" to 1L)))
        val (next, winner) = engine(storage, local).push("/n/_/col-0.md", "LOCAL\n", localUpdatedAt = 100.0, state = state, self = "me")
        assertNull(winner)
        assertEquals("REMOTE\n", local.getValue("/n/_/col-0.md"))
        assertEquals(mapOf("me" to 1L, "other" to 1L), next.entries["/n/_/col-0.md"]?.vv)
    }

    @Test
    fun pushColumnConcurrentOverlapPicksDeterministicWinner() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rRemote", serverModified = 300.0)
        val storage = FakeStorage(listOf(remote to "ZZZ\n"), remoteVv = mapOf("/n/_/col-0.md" to mapOf("other" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "AAA\n")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 1.0, base = "shared\n", vv = mapOf("me" to 1L)))
        val (next, winner) = engine(storage, local).push("/n/_/col-0.md", "AAA\n", localUpdatedAt = 1.0, state = state, self = "me")
        assertEquals("ZZZ\n", local.getValue("/n/_/col-0.md"), "AAA < ZZZ -> remote body wins deterministically")
        assertEquals(Winner.REMOTE, winner)
        assertEquals(mapOf("me" to 1L, "other" to 1L), next.entries["/n/_/col-0.md"]?.vv)
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
        val storage = FakeStorage(listOf(remote to "ZZZ\n"), remoteVv = mapOf("/n/_/col-0.md" to mapOf("other" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "AAA\n")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 1.0, base = "shared\n", vv = mapOf("me" to 1L)))
        val items = listOf(
            PushItem("/n/_/col-0.md", "AAA\n", 1.0),
            PushItem("/n/_/col-1.md", "new", 1.0),
        )
        val (next, conflicts) = engine(storage, local).pushBatch(items, state, self = "me")
        assertEquals(listOf("/n/_/col-0.md" to Winner.REMOTE), conflicts)
        assertEquals("ZZZ\n", local.getValue("/n/_/col-0.md"))
        assertEquals("new", storage.files.getValue("/n/_/col-1.md").second)
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
    fun pullFastForwardsWhenRemoteDominates() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rNew", 10.0)
        val storage = FakeStorage(listOf(remote to "remote body"),
            remoteVv = mapOf("/n/_/col-0.md" to mapOf("me" to 1L, "other" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "old body")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 5.0, base = "old body", vv = mapOf("me" to 1L)))

        val result = engine(storage, local).pull(root = "/n", state = state)

        assertEquals("remote body", local["/n/_/col-0.md"], "remote causally newer -> fast-forward")
        assertEquals(mapOf("me" to 1L, "other" to 1L), result.state.entries["/n/_/col-0.md"]?.vv)
        assertTrue(result.changedPaths.contains("/n/_/col-0.md"))
    }

    @Test
    fun pullKeepsLocalWhenLocalDominates() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rNew", 10.0)
        val storage = FakeStorage(listOf(remote to "remote body"),
            remoteVv = mapOf("/n/_/col-0.md" to mapOf("me" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "local body")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 5.0, base = "x", vv = mapOf("me" to 2L)))

        val result = engine(storage, local).pull(root = "/n", state = state)

        assertEquals("local body", local["/n/_/col-0.md"], "local causally newer -> keep + push")
        assertTrue(result.keptLocalPaths.contains("/n/_/col-0.md"))
        assertFalse(result.changedPaths.contains("/n/_/col-0.md"))
    }

    @Test
    fun pullMergesWhenConcurrent() = runTest {
        val base = "# A\nbody a\n\n# B\nbody b\n"
        val remote = RemoteFile("/n/_/col-0.md", "rNew", 10.0)
        val storage = FakeStorage(listOf(remote to "# A\nbody a\n\n# B\nbody b REMOTE\n"),
            remoteVv = mapOf("/n/_/col-0.md" to mapOf("other" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "# A\nbody a LOCAL\n\n# B\nbody b\n")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 5.0, base = base, vv = mapOf("me" to 1L)))

        val result = engine(storage, local).pull(root = "/n", state = state)

        val merged = local.getValue("/n/_/col-0.md")
        assertTrue(merged.contains("body a LOCAL") && merged.contains("body b REMOTE"))
        assertEquals(mapOf("me" to 1L, "other" to 1L), result.state.entries["/n/_/col-0.md"]?.vv)
        assertTrue(result.keptLocalPaths.contains("/n/_/col-0.md"))
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
    fun pullAdoptsNewRevWithoutRepushWhenContentIdentical() = runTest {
        // The churn bug: Drive's `version` bumps more than once per write, so our recorded rev lags
        // the listing even when nothing changed. The rev mismatch must NOT cause a re-push of
        // byte-identical content (which would bump the rev again — the self-sustaining loop that
        // re-uploaded all 17 files every reconcile). Adopt the new rev silently and converge.
        val remote = RemoteFile("/n/_/col-0.md", "rNEW", 10.0)
        val storage = FakeStorage(listOf(remote to "same body\n"),
            remoteVv = mapOf("/n/_/col-0.md" to mapOf("me" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "same body\n")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md",
            SyncEntry("rOLD", 5.0, base = "same body\n", vv = mapOf("me" to 1L)))

        val result = engine(storage, local).pull(root = "/n", state = state)

        assertFalse(result.keptLocalPaths.contains("/n/_/col-0.md"), "identical content must NOT be re-pushed")
        assertFalse(result.changedPaths.contains("/n/_/col-0.md"), "nothing actually changed")
        assertEquals("rNEW", result.state.entries["/n/_/col-0.md"]?.rev, "but the new rev is adopted so it converges")
    }

    @Test
    fun pushConflictAdoptsRevWithoutReuploadWhenContentIdentical() = runTest {
        // A push that rev-conflicts but whose content already matches the remote (stale rev token,
        // not a real edit) must adopt the rev and NOT force a redundant re-upload.
        val remote = RemoteFile("/n/_/col-0.md", "rNEW", 50.0)
        val storage = FakeStorage(listOf(remote to "identical\n"),
            remoteVv = mapOf("/n/_/col-0.md" to mapOf("me" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "identical\n")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md",
            SyncEntry("rOLD", 200.0, base = "identical\n", vv = mapOf("me" to 1L)))

        val (next, winner) = engine(storage, local)
            .push("/n/_/col-0.md", "identical\n", localUpdatedAt = 200.0, state = state, self = "me")

        assertNull(winner, "no conflict outcome — content already matches")
        assertEquals("rNEW", next.entries["/n/_/col-0.md"]?.rev, "adopt the remote rev")
        assertEquals("rNEW", storage.files.getValue("/n/_/col-0.md").first.rev,
            "no re-upload happened — the remote rev was not bumped")
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
        // Root EXISTS and is authoritatively empty -> the tracked file was genuinely deleted remotely.
        val storage = FakeStorage(emptyList(), rootExists = true)
        val local = mutableMapOf("/n/_/col-0.md" to "stale")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rA", 5.0))

        val result = engine(storage, local).pull(root = "/n", state = state)

        assertFalse(local.containsKey("/n/_/col-0.md"))
        assertFalse(result.state.entries.containsKey("/n/_/col-0.md"))
        assertTrue(result.changedPaths.contains("/n/_/col-0.md"))
    }

    @Test
    fun pullDoesNotDeleteWhenRemoteRootMissing() = runTest {
        // Remote root does not exist (fresh connect / unresolved folder). Local has tracked data with
        // confirmed revs. Pull must NOT treat the empty listing as remote deletions. (Vector A.)
        val storage = FakeStorage(emptyList(), rootExists = false)
        val local = mutableMapOf("/n/_/col-0.md" to "precious local data")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rA", 5.0))

        val result = engine(storage, local).pull(root = "/n", state = state)

        assertEquals("precious local data", local["/n/_/col-0.md"], "must not wipe local when root missing")
        assertTrue(result.state.entries.containsKey("/n/_/col-0.md"), "bookkeeping retained")
        assertTrue(result.changedPaths.isEmpty())
    }

    // --- base (merge ancestor) recording ---

    @Test
    fun cleanPushRecordsBaseForColumn() = runTest {
        val storage = FakeStorage(emptyList())
        val local = mutableMapOf<String, String>()
        val (state, _) = engine(storage, local)
            .push("/n/_/col-0.md", "hi\n", localUpdatedAt = 1.0, state = SyncStateDoc.EMPTY)
        assertEquals("hi\n", state.entries["/n/_/col-0.md"]?.base, "base records the synced content")
    }

    @Test
    fun pullDownloadRecordsBaseForColumn() = runTest {
        val remote = RemoteFile("/n/_/col-0.md", "rA", 10.0)
        val storage = FakeStorage(listOf(remote to "hello\n"))
        val local = mutableMapOf<String, String>()
        val result = engine(storage, local).pull(root = "/n", state = SyncStateDoc.EMPTY)
        assertEquals("hello\n", result.state.entries["/n/_/col-0.md"]?.base)
    }

    @Test
    fun manifestPushRecordsBaseAsHistoryMarker() = runTest {
        // A synced manifest records its content as a base — not for 3-way merge (layouts aren't
        // line-merged) but as the "this file HAS sync history" marker the no-history guard needs to
        // tell a pending re-push (rev cleared by a restore) from a never-synced file.
        val storage = FakeStorage(emptyList())
        val local = mutableMapOf<String, String>()
        val (state, _) = engine(storage, local)
            .push("/n/_/_canvas.json", "{}", localUpdatedAt = 1.0, state = SyncStateDoc.EMPTY)
        assertEquals("{}", state.entries["/n/_/_canvas.json"]?.base, "manifest records a history base")
    }

    @Test
    fun pushConflictMergesNonOverlappingColumnEdits() = runTest {
        val base = "# A\nbody a\n\n# B\nbody b\n"
        val remoteContent = "# A\nbody a\n\n# B\nbody b REMOTE\n"
        val remote = RemoteFile("/n/_/col-0.md", "rRemote", serverModified = 50.0)
        val storage = FakeStorage(listOf(remote to remoteContent), remoteVv = mapOf("/n/_/col-0.md" to mapOf("other" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "# A\nbody a LOCAL\n\n# B\nbody b\n")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 200.0, base = base, vv = mapOf("me" to 1L)))
        val (next, winner) = engine(storage, local).push("/n/_/col-0.md", local.getValue("/n/_/col-0.md"), localUpdatedAt = 200.0, state = state, self = "me")
        assertNull(winner)
        val mergedLocal = local.getValue("/n/_/col-0.md")
        assertTrue(mergedLocal.contains("body a LOCAL") && mergedLocal.contains("body b REMOTE"))
        assertEquals(mergedLocal, storage.files.getValue("/n/_/col-0.md").second)
        assertEquals(mapOf("me" to 1L, "other" to 1L), next.entries["/n/_/col-0.md"]?.vv)
    }

    @Test
    fun pushConflictManifestResolvesByStructureVectorNotTimestamp() = runTest {
        // A push-time rev race on a manifest must resolve by the structure vector (svv), NOT raw
        // timestamp LWW: a concurrent local structural edit whose svv dominates must force-push and
        // win even though the remote is "newer" by serverModified — otherwise "I changed my board
        // while connected" gets silently reverted.
        val home = CanvasName("")
        val mp = SyncPaths.manifestPath("/n", home)
        fun doc(cols: List<Int>) = CanvasDoc(home, null, cols.map { ColumnId(it.toString()) },
            listOf(ColumnLayoutSpec(ColumnId(cols.first().toString()), Split.VERTICAL, cols.drop(1).map { ColumnId(it.toString()) }))).encode()
        // Remote layout is causally BEHIND ({me:1}) but its serverModified is far later (would win LWW).
        val remoteManifest = ManifestVv.write(doc(listOf(0, 1)), "/n", home, emptyMap(), svv = mapOf("me" to 1L))
        val storage = FakeStorage(listOf(RemoteFile(mp, "rRemote", serverModified = 500.0) to remoteManifest))
        val localDoc = doc(listOf(0, 1, 2))   // local added a column (a structural edit)
        val local = mutableMapOf(mp to localDoc)
        val state = SyncStateDoc.EMPTY.with(mp,
            SyncEntry("rOld", 100.0, base = doc(listOf(0, 1)), vv = mapOf("me" to 2L)))   // svv dominates {me:1}
        val pushContent = ManifestVv.write(localDoc, "/n", home, emptyMap(), svv = mapOf("me" to 2L))

        val (_, winner) = engine(storage, local)
            .push(mp, pushContent, localUpdatedAt = 100.0, state = state, self = "me")

        assertNull(winner, "structure merges aren't surfaced as user conflicts")
        assertEquals(listOf(0, 1, 2).map { ColumnId(it.toString()) }, CanvasDoc.decode(storage.files.getValue(mp).second).columns,
            "local structural edit force-pushed despite the remote being newer by timestamp")
    }

    @Test
    fun pushCleanSeedsVvForColumn() = runTest {
        val storage = FakeStorage(emptyList())
        val local = mutableMapOf<String, String>()
        val (state, _) = engine(storage, local).push(
            "/n/_/col-0.md", "hi\n", localUpdatedAt = 1.0, state = SyncStateDoc.EMPTY, self = "me")
        assertEquals(mapOf("me" to 1L), state.entries["/n/_/col-0.md"]?.vv, "empty vv seeded with this device")
    }

    @Test
    fun pushCleanCarriesExistingVvForColumn() = runTest {
        val storage = FakeStorage(emptyList())
        val local = mutableMapOf<String, String>()
        val state0 = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry(null, 0.0, vv = mapOf("me" to 3L)))
        val (state, _) = engine(storage, local).push("/n/_/col-0.md", "hi\n", localUpdatedAt = 1.0, state = state0, self = "me")
        assertEquals(mapOf("me" to 3L), state.entries["/n/_/col-0.md"]?.vv)
    }

    @Test
    fun pushEqualVvButDivergentContentMergesNotAdoptsRemote() = runTest {
        // Equal vectors, but each side edited a DIFFERENT card -> must merge, never silently take remote.
        val base = "# A\nbody a\n\n# B\nbody b\n"
        val remoteContent = "# A\nbody a\n\n# B\nbody b REMOTE\n"
        val remote = RemoteFile("/n/_/col-0.md", "rRemote", serverModified = 50.0)
        val storage = FakeStorage(listOf(remote to remoteContent), remoteVv = mapOf("/n/_/col-0.md" to mapOf("x" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "# A\nbody a LOCAL\n\n# B\nbody b\n")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 1.0, base = base, vv = mapOf("x" to 1L)))

        val (next, winner) = engine(storage, local).push(
            "/n/_/col-0.md", local.getValue("/n/_/col-0.md"), localUpdatedAt = 1.0, state = state, self = "me")

        assertNull(winner)
        val merged = local.getValue("/n/_/col-0.md")
        assertTrue(merged.contains("body a LOCAL"), "local edit kept despite EQUAL vectors")
        assertTrue(merged.contains("body b REMOTE"), "remote edit kept despite EQUAL vectors")
    }

    @Test
    fun pullEqualVvButDivergentContentMerges() = runTest {
        val base = "# A\nbody a\n\n# B\nbody b\n"
        val remote = RemoteFile("/n/_/col-0.md", "rNew", 10.0)
        val storage = FakeStorage(listOf(remote to "# A\nbody a\n\n# B\nbody b REMOTE\n"),
            remoteVv = mapOf("/n/_/col-0.md" to mapOf("x" to 1L)))
        val local = mutableMapOf("/n/_/col-0.md" to "# A\nbody a LOCAL\n\n# B\nbody b\n")
        val state = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rOld", 5.0, base = base, vv = mapOf("x" to 1L)))

        val result = engine(storage, local).pull(root = "/n", state = state)

        val merged = local.getValue("/n/_/col-0.md")
        assertTrue(merged.contains("body a LOCAL") && merged.contains("body b REMOTE"), "EQUAL+divergent merges, no drop")
    }

    // --- engine-level two-device convergence (capstone) ---

    @Test
    fun twoDevicesConvergeOnConcurrentDifferentCardEdits() = runTest {
        val path = "/n/_/col-0.md"
        val base = "# A\nbody a\n\n# B\nbody b\n"
        // Shared cloud: starts holding `base` with a seed vector. Both devices begin synced to it.
        val storage = FakeStorage(listOf(RemoteFile(path, "r0", 1.0) to base))
        storage.putRemoteVv(path, mapOf("seed" to 1L))   // the manifest's vv for the base

        val localA = mutableMapOf(path to base)
        val localB = mutableMapOf(path to base)
        var stateA = SyncStateDoc.EMPTY.with(path, SyncEntry("r0", 1.0, base = base, vv = mapOf("seed" to 1L)))
        var stateB = SyncStateDoc.EMPTY.with(path, SyncEntry("r0", 1.0, base = base, vv = mapOf("seed" to 1L)))

        // A edits card A (bump A), pushes. Cloud now has A's content + A's vector.
        localA[path] = "# A\nbody a AAA\n\n# B\nbody b\n"
        stateA = stateA.with(path, stateA.entries[path]!!.copy(vv = bump(stateA.entries[path]!!.vv, "A")))
        val (a2, _) = engine(storage, localA).push(path, localA.getValue(path), 2.0, stateA, self = "A")
        stateA = a2
        storage.putRemoteVv(path, a2.entries[path]!!.vv)   // controller would write manifest vv

        // B edits card B concurrently (bump B), pushes -> RevConflict -> CONCURRENT merge against base.
        localB[path] = "# A\nbody a\n\n# B\nbody b BBB\n"
        stateB = stateB.with(path, stateB.entries[path]!!.copy(vv = bump(stateB.entries[path]!!.vv, "B")))
        val (b2, _) = engine(storage, localB).push(path, localB.getValue(path), 3.0, stateB, self = "B")
        stateB = b2
        storage.putRemoteVv(path, b2.entries[path]!!.vv)   // controller would write manifest vv

        // A pulls the converged result from the cloud.
        val ra = engine(storage, localA).pull("/n", stateA)
        stateA = ra.state

        // Convergence: both devices hold identical content with BOTH edits, and identical vectors.
        assertEquals(localB.getValue(path), localA.getValue(path), "devices converge to identical content")
        assertTrue(localA.getValue(path).contains("AAA"), "device A's edit survives")
        assertTrue(localA.getValue(path).contains("BBB"), "device B's edit survives")
        assertEquals(stateB.entries[path]!!.vv, stateA.entries[path]!!.vv, "vectors converge")
    }
}
