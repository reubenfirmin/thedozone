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
    ): Pair<SyncStateDoc, Winner?> {
        val (entry, winner) = pushOne(path, content, localUpdatedAt, state.entries[path]?.rev)
        return state.with(path, entry) to winner
    }

    /** Push many files concurrently; fold results into [state]. Returns it + any conflicts resolved. */
    suspend fun pushBatch(
        items: List<PushItem>,
        state: SyncStateDoc,
    ): Pair<SyncStateDoc, List<Pair<String, Winner>>> = coroutineScope {
        val snapshot = state.entries   // each push reads only its own prior rev — safe to parallelize
        val results = items.map { item ->
            async { item.path to pushOne(item.path, item.content, item.localUpdatedAt, snapshot[item.path]?.rev) }
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
    ): Pair<SyncEntry, Winner?> = try {
        val meta = storage.upload(path, content, ifMatchRev = knownRev)
        SyncEntry(meta.rev, localUpdatedAt) to null
    } catch (e: RevConflictException) {
        val (remoteContent, remoteMeta) = storage.download(path)
        when (resolveConflict(localUpdatedAt, remoteMeta.serverModified)) {
            Winner.LOCAL -> {
                val meta = storage.upload(path, content, ifMatchRev = null) // force overwrite
                SyncEntry(meta.rev, localUpdatedAt) to Winner.LOCAL
            }
            Winner.REMOTE -> {
                writeLocal(path, remoteContent)
                // Remote won: local now mirrors remote. We record remoteMeta.serverModified as the
                // entry's timestamp — the moment local was last reconciled to remote. It's a different
                // clock from local edit time, but only a baseline: a later genuine edit overwrites it
                // (SyncController stamps Date.now() before the next push), so it never skews a real edit.
                SyncEntry(remoteMeta.rev, remoteMeta.serverModified) to Winner.REMOTE
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
        val remote = storage.list(root).associateBy { it.path }
        val changed = mutableSetOf<String>()
        val keptLocal = mutableSetOf<String>()
        var next = state

        // Decide per remote file whether to download (overwriting local). Symmetric with push: when a
        // file changed on BOTH sides we apply last-write-wins instead of blindly trusting remote.
        val toDownload = remote.values.filter { meta ->
            val known = next.entries[meta.path]
            when {
                readLocal(meta.path) == null -> true        // we don't have it locally -> import
                known == null -> true                        // no sync history (first connect / post-wipe) -> import
                known.rev == meta.rev -> false               // up to date -> skip
                else -> when (resolveConflict(known.localUpdatedAt, meta.serverModified)) {
                    // Remote rev moved but local is newer: keep local, mark for push. Without this, an
                    // unpushed local edit was silently overwritten by an older remote copy.
                    Winner.LOCAL -> { keptLocal += meta.path; false }
                    Winner.REMOTE -> true
                }
            }
        }
        val downloaded = toDownload.map { meta -> async { storage.download(meta.path) } }.awaitAll()
        downloaded.forEach { (content, m) ->
            writeLocal(m.path, content)
            // serverModified is the reconcile baseline for this path (see pushOne REMOTE branch).
            next = next.with(m.path, SyncEntry(m.rev, m.serverModified))
            changed += m.path
        }

        // Remote deletions: ONLY for files we previously confirmed on the remote (entry.rev != null).
        // A null-rev entry is a pending local creation/edit that never reached the remote (e.g. its push
        // failed); treating it as a remote deletion would destroy unsynced local work — the data-loss bug.
        for (path in next.entries.keys.toList()) {
            val known = next.entries[path] ?: continue
            if (known.rev != null && !remote.containsKey(path)) {
                deleteLocal(path)
                next = next.without(path)
                changed += path
            }
        }

        PullResult(next, changed, keptLocal)
    }
}
