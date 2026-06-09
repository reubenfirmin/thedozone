package tdz.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tdz.model.localstorage.SyncEntry
import tdz.model.localstorage.SyncStateDoc

enum class Winner { LOCAL, REMOTE }

/** Last-write-wins by timestamp; ties favour remote (already-persisted) to avoid churn. */
fun resolveConflict(localUpdatedAt: Double, remoteServerModified: Double): Winner =
    if (localUpdatedAt > remoteServerModified) Winner.LOCAL else Winner.REMOTE

/**
 * Outcome of a [SyncEngine.pull]: the new state plus which local paths changed (for re-render).
 * [changedPaths] includes column files AND manifests, so it is not column-specific.
 * [keptLocalPaths] are files whose remote rev moved but where the LOCAL copy won the last-write-wins
 * decision, so pull left local untouched — the caller should push these up (see two-way reconcile).
 */
data class PullResult(
    val state: SyncStateDoc,
    val changedPaths: Set<String>,
    val keptLocalPaths: Set<String> = emptySet(),
)

/** One file to push: its remote [path], [content], and the local edit time. */
data class PushItem(val path: String, val content: String, val localUpdatedAt: Double)

/**
 * Stateless sync logic over a [RemoteStorage]. Decoupled from `Model` via the read/write/delete
 * callbacks so it is unit-testable with a fake storage and a plain map.
 *
 * Independent files are synced CONCURRENTLY: each file's outcome depends only on its own prior rev
 * (snapshotted from the input state), never on another file's result, so the per-path [SyncEntry]
 * updates are simply folded into the state after all the network calls complete. This turns N
 * sequential round-trips into ~one round-trip of wall-clock time (the browser caps real concurrency
 * per host). The single-file [push] remains for the debounced per-column edit path.
 */
class SyncEngine(
    private val storage: RemoteStorage,
    private val readLocal: (path: String) -> String?,
    private val writeLocal: (path: String, content: String) -> Unit,
    private val deleteLocal: (path: String) -> Unit,
) {

    /**
     * Push one file. Optimistic concurrency with the known rev; on conflict, download remote and
     * resolve last-write-wins. Returns the updated [state] and the [Winner] (null when no conflict).
     */
    suspend fun push(
        path: String,
        content: String,
        localUpdatedAt: Double,
        state: SyncStateDoc,
        self: String = DeviceId.get(),
    ): Pair<SyncStateDoc, Winner?> {
        val (entry, winner) = pushOne(
            path, content, localUpdatedAt, state.entries[path]?.rev, state.entries[path]?.base,
            self = self, knownVv = state.entries[path]?.vv ?: emptyMap(),
        )
        return state.with(path, entry) to winner
    }

    /** Push many files concurrently; fold results into [state]. Returns it + any conflicts resolved. */
    suspend fun pushBatch(
        items: List<PushItem>,
        state: SyncStateDoc,
        self: String = DeviceId.get(),
    ): Pair<SyncStateDoc, List<Pair<String, Winner>>> = coroutineScope {
        val snapshot = state.entries   // each push reads only its own prior rev — safe to parallelize
        val results = items.map { item ->
            async {
                item.path to pushOne(
                    item.path, item.content, item.localUpdatedAt, snapshot[item.path]?.rev, snapshot[item.path]?.base,
                    self = self, knownVv = snapshot[item.path]?.vv ?: emptyMap(),
                )
            }
        }.awaitAll()
        var s = state
        val conflicts = mutableListOf<Pair<String, Winner>>()
        results.forEach { (path, outcome) ->
            val (entry, winner) = outcome
            s = s.with(path, entry)
            if (winner != null) conflicts += path to winner
        }
        s to conflicts
    }

    /** The per-file push logic, returning a [SyncEntry] (not a whole state) so it can run in parallel. */
    private suspend fun pushOne(
        path: String,
        content: String,
        localUpdatedAt: Double,
        knownRev: String?,
        knownBase: String?,
        self: String,
        knownVv: VersionVector,
    ): Pair<SyncEntry, Winner?> {
        return try {
            val meta = storage.upload(path, content, ifMatchRev = knownRev)
            // For COLUMNS, carry the known vector forward, seeding an empty one with this device so two
            // independently-created columns are CONCURRENT (not spuriously EQUAL); for manifests, empty.
            val vv = if (SyncPaths.parseColumn(path) != null) knownVv.ifEmpty { mapOf(self to 1L) } else emptyMap()
            SyncEntry(meta.rev, localUpdatedAt, base = baseFor(path, content), vv = vv) to null
        } catch (e: RevConflictException) {
            val (remoteContent, remoteMeta) = storage.download(path)
            if (SyncPaths.parseColumn(path) != null) {
                val remoteVv = storage.columnVv(path)
                when (compareVv(knownVv, remoteVv)) {
                    Causal.REMOTE_DOMINATES -> {
                        // remote is causally ahead (has seen all local edits): adopt remote, nothing lost.
                        writeLocal(path, remoteContent)
                        return SyncEntry(remoteMeta.rev, localUpdatedAt, base = remoteContent, vv = remoteVv) to null
                    }
                    Causal.LOCAL_DOMINATES -> {
                        // local is causally ahead: force-upload local.
                        val meta = storage.upload(path, content, ifMatchRev = null)
                        return SyncEntry(meta.rev, localUpdatedAt, base = content, vv = knownVv) to null
                    }
                    // EQUAL with a real divergence (different content but neither clock dominates) is treated
                    // as concurrent so nothing is silently dropped: identical content merges to a no-op,
                    // divergent content merges (or hits the deterministic overlap tiebreak + backup).
                    Causal.CONCURRENT, Causal.EQUAL -> {
                        val merge = ColumnMerge.mergeColumn(local = content, base = knownBase, remote = remoteContent)
                        val join = pointwiseMax(knownVv, remoteVv)
                        if (!merge.hadConflict) {
                            writeLocal(path, merge.merged)
                            val meta = storage.upload(path, merge.merged, ifMatchRev = null)
                            return SyncEntry(meta.rev, localUpdatedAt, base = merge.merged, vv = join) to null
                        }
                        LocalBackup.snapshot()
                        // deterministic, symmetric overlap winner: keep the lexicographically-greater body so
                        // both devices converge on the same choice. Back up the loser (snapshot above).
                        val localWins = content >= remoteContent
                        val winnerContent = if (localWins) content else remoteContent
                        writeLocal(path, winnerContent)
                        val meta = storage.upload(path, winnerContent, ifMatchRev = null)
                        return SyncEntry(meta.rev, localUpdatedAt, base = winnerContent, vv = join) to
                            (if (localWins) Winner.LOCAL else Winner.REMOTE)
                    }
                }
            }
            // Manifests / non-columns: existing last-write-wins (unchanged).
            when (resolveConflict(localUpdatedAt, remoteMeta.serverModified)) {
                Winner.LOCAL -> {
                    val meta = storage.upload(path, content, ifMatchRev = null)
                    SyncEntry(meta.rev, localUpdatedAt, base = baseFor(path, content)) to Winner.LOCAL
                }
                Winner.REMOTE -> {
                    writeLocal(path, remoteContent)
                    SyncEntry(remoteMeta.rev, remoteMeta.serverModified, base = baseFor(path, remoteContent)) to Winner.REMOTE
                }
            }
        }
    }

    /** Delete a remote file and drop its bookkeeping. */
    suspend fun deleteRemote(path: String, state: SyncStateDoc): SyncStateDoc {
        storage.delete(path)
        return state.without(path)
    }

    /** Delete many remote files concurrently; drop their bookkeeping. */
    suspend fun deleteRemoteBatch(paths: List<String>, state: SyncStateDoc): SyncStateDoc = coroutineScope {
        paths.map { async { storage.delete(it) } }.awaitAll()
        var s = state
        paths.forEach { s = s.without(it) }
        s
    }

    /**
     * Reconcile remote -> local under [root]: download files whose rev changed (and unknown files)
     * CONCURRENTLY, delete locally any tracked file that vanished remotely. Returns the new state and
     * the set of local paths that changed so the controller can fire refresh events.
     */
    suspend fun pull(root: String, state: SyncStateDoc): PullResult = coroutineScope {
        val listing = storage.list(root)
        val remote = listing.files.associateBy { it.path }
        val changed = mutableSetOf<String>()
        val keptLocal = mutableSetOf<String>()
        var next = state

        // Intentionally sequential: a 3-way merge needs the downloaded remote content inline, so the
        // previous parallel download pass was traded for merge clarity (one round-trip per diverged file).
        for (meta in remote.values) {
            val known = next.entries[meta.path]
            val localContent = readLocal(meta.path)
            when {
                localContent == null -> {                                   // we don't have it -> import
                    val (content, m) = storage.download(meta.path)
                    writeLocal(m.path, content)
                    val vv = if (SyncPaths.parseColumn(m.path) != null) storage.columnVv(m.path) else emptyMap()
                    next = next.with(m.path, SyncEntry(m.rev, m.serverModified, base = baseFor(m.path, content), vv = vv))
                    changed += m.path
                }
                known != null && known.rev == meta.rev -> { /* up to date */ }
                else -> {                                                   // divergence (incl. first connect)
                    val (remoteContent, m) = storage.download(meta.path)
                    if (SyncPaths.parseColumn(meta.path) != null) {
                        val localVv = known?.vv ?: emptyMap()
                        // Perf: on real backends this downloads the canvas manifest once per divergent
                        // column; caching it per-pull is a deferred optimization.
                        val remoteVv = storage.columnVv(meta.path)
                        when (compareVv(localVv, remoteVv)) {
                            Causal.REMOTE_DOMINATES -> {                     // remote causally ahead -> adopt
                                writeLocal(m.path, remoteContent)
                                next = next.with(m.path, SyncEntry(m.rev, m.serverModified, base = remoteContent, vv = remoteVv))
                                changed += m.path
                            }
                            Causal.LOCAL_DOMINATES -> {                      // local causally ahead -> keep + push
                                keptLocal += m.path
                            }
                            // EQUAL with a real divergence (different content but neither clock dominates) is treated
                            // as concurrent so nothing is silently dropped: identical content merges to a no-op,
                            // divergent content merges (or hits the deterministic overlap tiebreak + backup).
                            Causal.CONCURRENT, Causal.EQUAL -> {
                                val merge = ColumnMerge.mergeColumn(local = localContent, base = known?.base, remote = remoteContent)
                                val join = pointwiseMax(localVv, remoteVv)
                                if (!merge.hadConflict) {
                                    writeLocal(m.path, merge.merged)
                                    next = next.with(m.path, SyncEntry(m.rev, known?.localUpdatedAt ?: m.serverModified, base = merge.merged, vv = join))
                                    changed += m.path
                                    keptLocal += m.path   // push merged up so the remote converges
                                } else {
                                    LocalBackup.snapshot()
                                    // deterministic symmetric winner: keep lexicographically-greater body.
                                    val winner = if (localContent >= remoteContent) localContent else remoteContent
                                    writeLocal(m.path, winner)
                                    next = next.with(m.path, SyncEntry(m.rev, known?.localUpdatedAt ?: m.serverModified, base = winner, vv = join))
                                    changed += m.path
                                    keptLocal += m.path
                                }
                            }
                        }
                    } else {
                        // Manifests / non-columns: existing last-write-wins (they carry vectors but aren't merged).
                        when (resolveConflict(known?.localUpdatedAt ?: 0.0, m.serverModified)) {
                            Winner.LOCAL -> keptLocal += m.path
                            Winner.REMOTE -> {
                                writeLocal(m.path, remoteContent)
                                next = next.with(m.path, SyncEntry(m.rev, m.serverModified, base = baseFor(m.path, remoteContent)))
                                changed += m.path
                            }
                        }
                    }
                }
            }
        }

        // Remote deletions: ONLY when the remote root is confirmed to exist (an authoritative listing)
        // AND only for files we previously confirmed on the remote (entry.rev != null). A non-existent
        // root (fresh connect / unresolved folder) yields an empty listing that must NOT be read as
        // mass deletion — that was the wipe. A null-rev entry is an unsynced local creation; deleting it
        // would destroy local work.
        if (listing.rootExists) {
            for (path in next.entries.keys.toList()) {
                val known = next.entries[path] ?: continue
                if (known.rev != null && !remote.containsKey(path)) {
                    deleteLocal(path)
                    next = next.without(path)
                    changed += path
                }
            }
        }

        PullResult(next, changed, keptLocal)
    }

    /** Base (merge ancestor) is only meaningful for mergeable column files, never for JSON manifests. */
    private fun baseFor(path: String, content: String): String? =
        if (SyncPaths.parseColumn(path) != null) content else null
}
