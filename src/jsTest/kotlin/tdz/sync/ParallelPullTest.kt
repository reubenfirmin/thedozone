package tdz.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import tdz.model.localstorage.SyncStateDoc
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pull's network phase must run CONCURRENTLY. The field instrumentation showed a reconcile
 * spending 29.9s of 32.5s in the pull because each divergent file's downloads ran in series
 * (~1.5s each × 18 files), while the push phase moved 17 files in 2.6s by running them at once.
 * The decision loop stays sequential (merge clarity); only the fetches overlap.
 */
class ParallelPullTest {

    @Test
    fun pullFetchesDivergentFilesConcurrently() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        val files = (1..4).map { RemoteFile("/n/_/col-$it.md", "r$it", it.toDouble()) }
        val storage = object : RemoteStorage {
            override suspend fun list(folder: String) = RemoteListing(files, rootExists = true)
            override suspend fun download(path: String): Pair<String, RemoteFile> {
                inFlight++
                maxInFlight = max(maxInFlight, inFlight)
                delay(25)   // a network round trip; concurrent calls overlap here
                inFlight--
                return "content of $path" to files.first { it.path == path }
            }
            override suspend fun upload(path: String, content: String, ifMatchRev: String?) =
                RemoteFile(path, "rX", 99.0)
            override suspend fun delete(path: String) {}
            override suspend fun revoke() {}
            override suspend fun columnVv(path: String): VersionVector = emptyMap()
        }
        val local = mutableMapOf<String, String>()
        val engine = SyncEngine(
            storage = storage,
            readLocal = { local[it] },
            writeLocal = { p, c -> local[p] = c },
            deleteLocal = { local.remove(it); Unit },
        )

        val result = engine.pull(root = "/n", state = SyncStateDoc.EMPTY)

        assertEquals(4, result.changedPaths.size, "all four files imported")
        assertEquals(4, local.size)
        assertTrue(maxInFlight >= 2,
            "downloads must overlap — a sequential pull is the 30-second sync (max in flight: $maxInFlight)")
    }
}
