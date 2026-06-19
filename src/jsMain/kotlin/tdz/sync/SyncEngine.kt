package tdz.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.localstorage.CanvasDoc
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

    /** True when remote ops can proceed without an interactive auth prompt (see [RemoteStorage.isReady]). */
    fun isReady(): Boolean = storage.isReady()

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
            // independently-created columns are CONCURRENT (not spuriously EQUAL). For MANIFESTS, carry
            // the STRUCTURE vector forward too (it must survive every push so layout stays causally
            // tracked); only never-seeded layout-only files end up empty.
            val vv = if (SyncPaths.parseColumn(path) != null) knownVv.ifEmpty { mapOf(self to 1L) } else knownVv
            SyncEntry(meta.rev, localUpdatedAt, base = baseFor(path, content), vv = vv) to null
        } catch (e: RevConflictException) {
            val (remoteContent, remoteMeta) = storage.download(path)
            if (SyncPaths.parseColumn(path) != null) {
                if (remoteContent == content) {
                    // Stale rev token, not a real edit (Drive bumps `version` more than once per
                    // write): the content already matches the remote, so adopt its rev instead of
                    // forcing a redundant re-upload.
                    return SyncEntry(remoteMeta.rev, localUpdatedAt, base = content, vv = knownVv) to null
                }
                val remoteVv = storage.columnVv(path)
                // No-history merges rather than letting a fabricated/absent clock pick a winner.
                val causal = causalWithHistory(knownRev != null || knownBase != null, knownVv, remoteVv)
                when (causal) {
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
                    // divergent content merges (or hits the deterministic overlap tiebreak).
                    Causal.CONCURRENT, Causal.EQUAL -> {
                        val merge = ColumnMerge.mergeColumn(local = content, base = knownBase, remote = remoteContent)
                        val join = pointwiseMax(knownVv, remoteVv)
                        if (!merge.hadConflict) {
                            writeLocal(path, merge.merged)
                            val meta = storage.upload(path, merge.merged, ifMatchRev = null)
                            return SyncEntry(meta.rev, localUpdatedAt, base = merge.merged, vv = join) to null
                        }
                        // deterministic, symmetric overlap winner: keep the lexicographically-greater body so
                        // both devices converge on the same choice.
                        val localWins = content >= remoteContent
                        val winnerContent = if (localWins) content else remoteContent
                        writeLocal(path, winnerContent)
                        val meta = storage.upload(path, winnerContent, ifMatchRev = null)
                        return SyncEntry(meta.rev, localUpdatedAt, base = winnerContent, vv = join) to
                            (if (localWins) Winner.LOCAL else Winner.REMOTE)
                    }
                }
            }
            // Manifests: resolve a push-time rev race by the SAME causal svv rules as the pull side,
            // not raw timestamp LWW. LWW here could silently drop a concurrent local structural edit
            // (e.g. "I cleared my board") whenever the remote's serverModified happened to be later.
            val localSvv = knownVv
            val remoteSvv = ManifestVv.readSvv(remoteContent)
            val join = pointwiseMax(localSvv, remoteSvv)
            when (causalWithHistory(knownRev != null || knownBase != null, localSvv, remoteSvv)) {
                Causal.REMOTE_DOMINATES -> {                     // remote layout newer -> adopt, nothing lost
                    writeLocal(path, remoteContent)
                    SyncEntry(remoteMeta.rev, remoteMeta.serverModified, base = baseFor(path, remoteContent), vv = remoteSvv) to null
                }
                Causal.LOCAL_DOMINATES -> {                      // local layout newer -> force-upload local
                    val meta = storage.upload(path, content, ifMatchRev = null)
                    SyncEntry(meta.rev, localUpdatedAt, base = baseFor(path, content), vv = join) to null
                }
                Causal.CONCURRENT, Causal.EQUAL -> {             // neither newer -> merge layouts, keep both
                    val merged = ManifestMerge.mergeLayouts(local = content, remote = remoteContent)
                    writeLocal(path, merged)
                    val meta = storage.upload(path, merged, ifMatchRev = null)
                    SyncEntry(meta.rev, localUpdatedAt, base = baseFor(path, merged), vv = join) to null
                }
            }
        }
    }

    /** Every remote file path under [root] — used by the destructive "sync TO" mirror to find files
     *  that exist remotely but not locally (and must be deleted). Empty when the folder doesn't exist. */
    suspend fun remotePaths(root: String): Set<String> = storage.list(root).files.map { it.path }.toSet()

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

    /** Delete many remote files concurrently WITHOUT touching local bookkeeping — for column deletions
     *  the caller records as tombstones (which must survive, unlike the dropped entries above). */
    suspend fun deleteRemoteFiles(paths: List<String>): Unit = coroutineScope {
        paths.map { async { storage.delete(it) } }.awaitAll()
        Unit
    }

    /**
     * Reconcile remote -> local under [root]: download files whose rev changed (and unknown files)
     * CONCURRENTLY, delete locally any tracked file that vanished remotely. Returns the new state and
     * the set of local paths that changed so the controller can fire refresh events.
     */
    /** One prefetched remote file: its content, fresh metadata, and (columns) version vector. */
    private class Fetched(val content: String, val meta: RemoteFile, val vv: VersionVector)

    suspend fun pull(root: String, state: SyncStateDoc): PullResult = coroutineScope {
        val listing = storage.list(root)
        val remote = listing.files.associateBy { it.path }
        val changed = mutableSetOf<String>()
        val keptLocal = mutableSetOf<String>()
        var next = state

        // Network phase: fetch every file the decision loop will need CONCURRENTLY (content + vv).
        // The loop below stays sequential for merge clarity, but its per-file round trips —
        // previously ~1.5s each, in series, the whole cost of a slow sync — collapse into one
        // parallel wave (the browser pipelines per-host).
        val fetched: Map<String, Fetched> = remote.values
            .filter { meta ->
                readLocal(meta.path) == null || state.entries[meta.path]?.rev != meta.rev
            }
            .map { meta ->
                async {
                    val (content, m) = storage.download(meta.path)
                    val vv = if (SyncPaths.parseColumn(meta.path) != null) storage.columnVv(meta.path)
                             else emptyMap<String, Long>()
                    meta.path to Fetched(content, m, vv)
                }
            }.awaitAll().toMap()

        for (meta in remote.values) {
            val known = next.entries[meta.path]
            val localContent = readLocal(meta.path)
            when {
                localContent == null -> {                                   // we don't have it -> import
                    val f = fetched.getValue(meta.path)
                    val m = f.meta
                    // Don't resurrect a column we tombstoned: if its remote file lingers (the delete
                    // didn't reach this device's remote yet) and the remote carries no edit our delete
                    // never saw, honor the deletion — skip the import and remove the stale remote file.
                    val tomb = if (SyncPaths.parseColumn(meta.path) != null) known?.takeIf { it.tombstone }?.vv else null
                    if (tomb != null && !hasEditBeyond(f.vv, tomb)) {
                        runCatching { storage.delete(m.path) }
                    } else {
                        writeLocal(m.path, f.content)
                        next = next.with(m.path, SyncEntry(m.rev, m.serverModified, base = baseFor(m.path, f.content), vv = f.vv))
                        changed += m.path
                    }
                }
                known != null && known.rev == meta.rev -> { /* up to date */ }
                else -> {                                                   // divergence (incl. first connect)
                    val f = fetched.getValue(meta.path)
                    val remoteContent = f.content
                    val m = f.meta
                    if (SyncPaths.parseColumn(meta.path) != null) {
                        val localVv = known?.vv ?: emptyMap()
                        val remoteVv = f.vv
                        if (remoteContent == localContent) {
                            // Byte-identical: only Drive's rev token moved (its `version` bumps more
                            // than once per write, so our recorded rev lags the listing even when
                            // nothing changed). Adopt the rev to converge — re-pushing identical
                            // bytes would just bump it again, the self-sustaining churn that
                            // re-uploaded every file every sync. Not changed, not kept-local.
                            next = next.with(m.path, SyncEntry(m.rev, known?.localUpdatedAt ?: m.serverModified,
                                base = remoteContent, vv = pointwiseMax(localVv, remoteVv)))
                        } else {
                        // No-history (fresh connect / content created while sync was off) unions both
                        // sides; otherwise compare clocks. See [causalWithHistory].
                        val causal = causalWithHistory(known?.rev != null || known?.base != null, localVv, remoteVv)
                        when (causal) {
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
                            // divergent content merges (or hits the deterministic overlap tiebreak).
                            Causal.CONCURRENT, Causal.EQUAL -> {
                                val merge = ColumnMerge.mergeColumn(local = localContent, base = known?.base, remote = remoteContent)
                                val join = pointwiseMax(localVv, remoteVv)
                                if (!merge.hadConflict) {
                                    writeLocal(m.path, merge.merged)
                                    next = next.with(m.path, SyncEntry(m.rev, known?.localUpdatedAt ?: m.serverModified, base = merge.merged, vv = join))
                                    changed += m.path
                                    keptLocal += m.path   // push merged up so the remote converges
                                } else {
                                    // deterministic symmetric winner: keep lexicographically-greater body.
                                    val winner = if (localContent >= remoteContent) localContent else remoteContent
                                    writeLocal(m.path, winner)
                                    next = next.with(m.path, SyncEntry(m.rev, known?.localUpdatedAt ?: m.serverModified, base = winner, vv = join))
                                    changed += m.path
                                    keptLocal += m.path
                                }
                            }
                        }
                        }
                    } else if (SyncPaths.isManifest(meta.path)) {
                        // Layout IS content: resolve the manifest by the SAME causal rules as a column,
                        // using the STRUCTURE version vector (svv). Newer layout wins; concurrent (or
                        // no-history, where a stale remote may carry this device's old clock) merges so
                        // neither side's columns are dropped or left unplaced.
                        val localSvv = known?.vv ?: emptyMap()
                        val remoteSvv = ManifestVv.readSvv(remoteContent)
                        val join = pointwiseMax(localSvv, remoteSvv)
                        // Every branch learns the other side's tombstones (the `deleted` set rides in the
                        // manifest) so an adopted/kept deletion keeps propagating and the import gate can
                        // suppress a lingering remote file — not just the concurrent-merge case.
                        val canvas = SyncPaths.parseManifest(m.path, root)
                        if (sameStructure(localContent, remoteContent)) {
                            // Only the rev/svv moved — converge without rewriting (avoids churn).
                            if (canvas != null) next = learnRemoteTombstones(remoteContent, canvas, root, columnsOf(localContent), next)
                            next = next.with(m.path, SyncEntry(m.rev, known?.localUpdatedAt ?: m.serverModified, base = remoteContent, vv = join))
                        } else {
                            // No-history means the svv can't be trusted -> merge. See [causalWithHistory].
                            val causal = causalWithHistory(known?.rev != null || known?.base != null, localSvv, remoteSvv)
                            when (causal) {
                                Causal.REMOTE_DOMINATES -> {                    // remote layout newer -> adopt
                                    writeLocal(m.path, remoteContent)
                                    if (canvas != null) next = learnRemoteTombstones(remoteContent, canvas, root, columnsOf(remoteContent), next)
                                    next = next.with(m.path, SyncEntry(m.rev, m.serverModified, base = remoteContent, vv = remoteSvv))
                                    changed += m.path
                                }
                                Causal.LOCAL_DOMINATES -> {                     // local layout newer -> keep + push
                                    // Adopt the remote rev so the follow-up push overwrites cleanly.
                                    if (canvas != null) next = learnRemoteTombstones(remoteContent, canvas, root, columnsOf(localContent), next)
                                    next = next.with(m.path, known!!.copy(rev = m.rev, vv = join))
                                    keptLocal += m.path
                                }
                                Causal.CONCURRENT, Causal.EQUAL -> {            // neither newer -> merge, keep both
                                    val union = ManifestMerge.mergeLayouts(local = localContent, remote = remoteContent)
                                    // Fold in tombstones: an additive union alone resurrects a column
                                    // deleted on the other side. Keep-edit-else-delete decides membership.
                                    val tr = if (canvas != null) applyTombstones(union, canvas, root, next, remoteContent)
                                             else TombstoneResult(union, next, emptySet(), emptySet())
                                    next = tr.next
                                    tr.dropPaths.forEach { if (readLocal(it) != null) deleteLocal(it) }   // drop deleted columns' local content
                                    writeLocal(m.path, tr.merged)
                                    next = next.with(m.path, SyncEntry(m.rev, m.serverModified, base = tr.merged, vv = join))
                                    changed += m.path
                                    if (!sameStructure(tr.merged, remoteContent)) keptLocal += m.path
                                    keptLocal += tr.keptPending   // re-push columns that survived a tombstone via a concurrent edit
                                }
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

    /** Records the last-synced content as a base. For columns it's the 3-way merge ancestor; for
     *  manifests it's only a "this file HAS sync history" marker — so a pending re-push (rev cleared
     *  by a restore) is distinguished from a never-synced file (the no-history guard needs both
     *  rev AND base absent). Layout-only files that never sync stay base-less. */
    private fun baseFor(path: String, content: String): String? =
        if (SyncPaths.parseColumn(path) != null || SyncPaths.isManifest(path)) content else null

    /**
     * Causal class of local vs remote vectors, with the no-history override: a file we've never
     * synced (no rev AND no base) can't trust its vv — a stale remote may carry THIS device's old
     * clock from a previous sync life (DeviceId survives disconnect) and look "causally ahead" of
     * brand-new local content — so it is treated as CONCURRENT (merge; nothing silently wins) rather
     * than compared. Shared by the column push, the column pull, and the manifest (svv) pull so the
     * rule can never drift between them.
     */
    private fun causalWithHistory(hasHistory: Boolean, local: VersionVector, remote: VersionVector): Causal =
        if (hasHistory) compareVv(local, remote) else Causal.CONCURRENT

    /** True when [v] carries an edit the deletion-clock [t] never saw (a component where v > t) —
     *  i.e. the column was edited concurrently with (or after) the delete, so the delete must NOT win. */
    private fun hasEditBeyond(v: VersionVector, t: VersionVector): Boolean =
        (v.keys + t.keys).any { (v[it] ?: 0L) > (t[it] ?: 0L) }

    /** Outcome of folding tombstones into a merged manifest: the corrected structure, the updated
     *  state (tombstones learned/kept, survivors marked pending), the column files to delete locally,
     *  and the columns kept despite a tombstone (a concurrent edit) that must be re-pushed. */
    private class TombstoneResult(
        val merged: String, val next: SyncStateDoc,
        val dropPaths: Set<String>, val keptPending: Set<String>,
    )

    /**
     * Keep-edit-else-delete. Fold union(local, remote) tombstones into [merged] (the additive layout
     * union): a tombstoned column is DROPPED unless its content vv carries an edit the deletion never
     * saw, in which case it's KEPT (and its tombstone cleared). Learns remote tombstones into state so
     * they propagate; survivors are marked pending so the deletion sweep spares them and they re-push.
     */
    private fun applyTombstones(merged: String, canvas: CanvasName, root: String, state: SyncStateDoc, remoteContent: String): TombstoneResult {
        val doc = runCatching { CanvasDoc.decode(merged) }.getOrNull()
            ?: return TombstoneResult(merged, state, emptySet(), emptySet())
        val localTomb = state.entries.mapNotNull { (p, e) ->
            if (!e.tombstone) return@mapNotNull null
            SyncPaths.parseColumn(p, root)?.takeIf { it.first.normalized() == canvas.normalized() }?.let { it.second to e.vv }
        }.toMap()
        val remoteTomb = ManifestVv.readDeleted(remoteContent)
        val union = (localTomb.keys + remoteTomb.keys)
            .associateWith { pointwiseMax(localTomb[it] ?: emptyMap(), remoteTomb[it] ?: emptyMap()) }
        if (union.isEmpty()) return TombstoneResult(merged, state, emptySet(), emptySet())
        val remoteColVvs = ManifestVv.read(remoteContent, root, canvas)
        var s = state
        val drop = mutableSetOf<ColumnId>()
        val dropPaths = mutableSetOf<String>()
        val keptPending = mutableSetOf<String>()
        for ((id, t) in union) {
            val cp = SyncPaths.columnPath(root, canvas, id)
            val v = pointwiseMax(s.entries[cp]?.takeIf { !it.tombstone }?.vv ?: emptyMap(), remoteColVvs[cp] ?: emptyMap())
            if (id in doc.columns && hasEditBeyond(v, t)) {
                // Concurrent edit wins: keep the column, clear its tombstone, mark it pending so the
                // remote-deletion sweep spares it and the two-way push re-uploads it.
                keptPending += cp
                s = s.with(cp, (s.entries[cp] ?: SyncEntry(rev = null, localUpdatedAt = 0.0)).copy(rev = null, tombstone = false, vv = v))
            } else {
                // Deletion stands: learn/keep the tombstone (so it propagates), drop the column.
                if (id in doc.columns) { drop += id; dropPaths += cp }
                s = s.with(cp, SyncEntry(rev = null, localUpdatedAt = s.entries[cp]?.localUpdatedAt ?: 0.0, vv = t, tombstone = true))
            }
        }
        if (drop.isEmpty()) return TombstoneResult(merged, s, emptySet(), keptPending)
        val keptColumns = doc.columns.filterNot { it in drop }
        val keptLayouts = doc.layouts.filter { it.columnId !in drop }
            .map { spec -> spec.copy(contains = spec.contains.filterNot { it in drop }) }
        val newDoc = CanvasDoc(doc.name, doc.parentName, keptColumns, keptLayouts).encode()
        return TombstoneResult(newDoc, s, dropPaths, keptPending)
    }

    /** The column ids of [content], or empty if it can't be decoded. */
    private fun columnsOf(content: String): Set<ColumnId> =
        runCatching { CanvasDoc.decode(content).columns.toSet() }.getOrNull() ?: emptySet()

    /**
     * Record [remoteContent]'s tombstones into [state] for columns the resolved structure does NOT keep
     * ([liveColumns]) — so a deletion this device adopts/keeps from the manifest keeps propagating and
     * the import gate can suppress a lingering remote file. A column the resolved structure KEEPS is
     * never tombstoned (the dominant side deliberately kept it). Pointwise-max with any existing local
     * tombstone. Used by the non-concurrent branches; the concurrent branch learns via [applyTombstones].
     */
    private fun learnRemoteTombstones(remoteContent: String, canvas: CanvasName, root: String, liveColumns: Set<ColumnId>, state: SyncStateDoc): SyncStateDoc {
        val remoteTomb = ManifestVv.readDeleted(remoteContent)
        if (remoteTomb.isEmpty()) return state
        var s = state
        for ((id, t) in remoteTomb) {
            if (id in liveColumns) continue
            val cp = SyncPaths.columnPath(root, canvas, id)
            val prior = s.entries[cp]?.takeIf { it.tombstone }?.vv ?: emptyMap()
            s = s.with(cp, SyncEntry(rev = null, localUpdatedAt = s.entries[cp]?.localUpdatedAt ?: 0.0,
                vv = pointwiseMax(prior, t), tombstone = true))
        }
        return s
    }

    /** Two manifests describe the same board STRUCTURE (columns + layout), ignoring sync vectors. */
    private fun sameStructure(a: String, b: String): Boolean {
        val da = runCatching { CanvasDoc.decode(a) }.getOrNull() ?: return false
        val db = runCatching { CanvasDoc.decode(b) }.getOrNull() ?: return false
        return da.columns == db.columns && da.layouts == db.layouts
    }
}
