package tdz.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.js.Date
import tdz.controller.events.Event
import tdz.controller.events.SyncConflict
import tdz.model.Model
import tdz.model.localstorage.SyncEntry
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus

/**
 * The first-sync direction logic, owned by [ConnectionLifecycle] (which builds the engine, then hands
 * off here). Runs through the SAME [SyncSession] gate as [SyncController.reconcile], so an initial
 * sync and a background reconcile can never run concurrently against one engine/state.
 *
 * - [run] (FROM): pull + MERGE, then push every local file up (causal union; nothing lost).
 * - [runTo] (TO/mirror): force-push every local file and delete any remote file not present locally.
 * - [decide]: after auth, pick the direction (remote empty -> TO; brand-new device -> FROM; else ask).
 */
internal class InitialSync(
    private val model: Model,
    private val eventBus: EventBus<Event>,
    private val scope: CoroutineScope,
    private val session: SyncSession,
    private val adapter: ModelPathAdapter,
    private val access: SyncStateAccess,
    private val fail: (Throwable) -> Unit,
) {
    private fun root() = access.root()
    private fun state() = access.state()
    private fun saveState(s: SyncStateDoc) = access.saveState(s)

    /** The user's answer to [SyncState.ChoosingDirection] (the modal calls this). */
    fun choose(mode: SyncController.InitialSyncMode) {
        if (session.engine == null) return
        scope.launch {
            when (mode) {
                SyncController.InitialSyncMode.FROM -> run()
                SyncController.InitialSyncMode.TO -> runTo()
            }
        }
    }

    /**
     * After auth: check the remote, then the local device, to pick the first-sync direction.
     * - remote EMPTY → this is genuinely a first connect; just push local up (TO).
     * - local has NO content AND NO history → a brand-new device; merge FROM the folder (FROM).
     * - otherwise both sides have something → park in [SyncState.ChoosingDirection] for the user.
     */
    suspend fun decide(hadHistory: Boolean) {
        val eng = session.engine ?: return
        // Buffered edits must be on disk before hasLocalContent()/allCanvasNames() scan localStorage,
        // or a board the user just made looks empty and we'd wrongly auto-merge FROM the folder.
        tdz.model.localstorage.flushPendingWrites()
        val remotePaths = runCatching { eng.remotePaths(root()) }.getOrElse { fail(it); return }
        val localContent = hasLocalContent()
        SyncLog.log("connect decision: remoteFiles=${remotePaths.size} hasLocalContent=$localContent " +
            "hadHistory=$hadHistory -> ${when {
                remotePaths.isEmpty() -> "TO (remote empty)"
                !localContent && !hadHistory -> "FROM (brand-new device)"
                else -> "ASK (both sides have content)"
            }}")
        when {
            // Reuse the listing (and flush) just done — no second remote round-trip on this path.
            remotePaths.isEmpty() -> runTo(remotePaths)              // nothing in the folder → push local
            !localContent && !hadHistory -> run()                    // brand-new device → merge from folder
            else -> SyncStateStore.set(SyncState.ChoosingDirection, eventBus)   // conflict → ask
        }
    }

    /**
     * First-connect two-way sync: pull remote into local, then push EVERY local canvas (manifest +
     * columns) up. Without this, connecting with existing local data leaves Dropbox empty until each
     * column is next edited. Per-file LWW still applies via [SyncEngine.push]; for files imported by
     * the pull, the re-push is redundant-but-harmless. Runs through the shared reconcile gate.
     */
    suspend fun run() {
        val eng = session.engine ?: return
        if (!session.tryBeginReconcile()) return   // a reconcile/initial-sync is already in flight
        SyncStateStore.set(SyncState.Reconciling, eventBus)
        val t0 = Date.now()
        SyncLog.resetCounters()
        SyncLog.log("initial sync FROM started (build ${SyncLog.BUILD})")
        tdz.model.localstorage.flushPendingWrites()   // buffered edits must be on disk before the sync reads local
        try {
            runCatching {
                // Seed local column vectors BEFORE pulling: otherwise an unseeded local column would be
                // silently overwritten by a remote that another device already seeded (REMOTE_DOMINATES).
                var s = seedLocalVvs(state())
                saveState(s)
                val tPull = Date.now()
                val pulled = eng.pull(root(), s)            // remote -> local
                s = pulled.state
                SyncLog.log("initial pull: ${pulled.changedPaths.size} changed (${SyncLog.since(tPull)})")
                val tPush = Date.now()
                s = pushAllLocal(eng, s)                    // local -> remote (manifests + columns)
                SyncLog.log("initial push-all done (${SyncLog.since(tPush)})")
                saveState(s)
                SyncStateStore.set(SyncState.Synced, eventBus)
                SyncLog.log("initial sync finished in ${SyncLog.since(t0)} — ${SyncLog.summary()}")
            }.onFailure {
                SyncLog.log("initial sync FAILED after ${SyncLog.since(t0)} — ${SyncLog.summary()}: ${it.message}")
                fail(it)
            }
        } finally {
            session.endReconcile()
        }
    }

    /**
     * "Sync TO": assert the local board is correct and make the remote MIRROR it. Force-push every
     * local file (overwrite — empty bookkeeping means each upload has no rev to match) and DELETE any
     * remote file not present locally. NO pull, NO merge — the destructive "my device wins" direction
     * the user explicitly chooses. With an empty remote it just pushes local (converges with FROM).
     */
    suspend fun runTo(knownRemotePaths: Set<String>? = null) {
        val eng = session.engine ?: return
        if (!session.tryBeginReconcile()) return   // a reconcile/initial-sync is already in flight
        SyncStateStore.set(SyncState.Reconciling, eventBus)
        val t0 = Date.now()
        SyncLog.resetCounters()
        SyncLog.log("initial sync TO started (build ${SyncLog.BUILD})")
        // [decide] already flushed + listed and hands the paths down; only do it ourselves when called
        // fresh (an explicit re-choice via the modal, or connect(mode = TO)).
        if (knownRemotePaths == null) tdz.model.localstorage.flushPendingWrites()
        try {
            runCatching {
                saveState(SyncStateDoc.EMPTY)   // no known revs -> every push force-overwrites
                // The exact set of files the local board should occupy on the remote.
                val localPaths = buildSet {
                    for (name in model.allCanvasNames()) {
                        val canvas = model.getCanvas(name) ?: continue
                        add(SyncPaths.manifestPath(root(), name))
                        for (col in canvas.columns) if (model.getCanvasColumnRaw(name, col) != null) {
                            add(SyncPaths.columnPath(root(), name, col))
                        }
                    }
                }
                // Delete every remote file that isn't part of the local board — the remote must MIRROR.
                val extras = (knownRemotePaths ?: eng.remotePaths(root())).filter { it !in localPaths }
                if (extras.isNotEmpty()) {
                    SyncLog.log("TO: deleting ${extras.size} remote file(s) not present locally")
                    saveState(eng.deleteRemoteBatch(extras, state()))
                }
                saveState(pushAllLocal(eng, state()))   // force-push all local (overwrite)
                SyncStateStore.set(SyncState.Synced, eventBus)
                SyncLog.log("initial sync TO finished in ${SyncLog.since(t0)} — ${SyncLog.summary()}")
            }.onFailure {
                SyncLog.log("initial sync TO FAILED after ${SyncLog.since(t0)} — ${SyncLog.summary()}: ${it.message}")
                fail(it)
            }
        } finally {
            session.endReconcile()
        }
    }

    /**
     * Seed every local column that has no version vector yet with this device's component (`{self:1}`).
     * MUST run BEFORE the first pull on connect: an unseeded local column (empty vv) compared against a
     * remote another device already seeded would classify as REMOTE_DOMINATES and silently overwrite the
     * local content. With both sides seeded by different devices the comparison is CONCURRENT, so they
     * merge instead of one silently winning. Idempotent — already-seeded columns are left untouched.
     */
    private fun seedLocalVvs(start: SyncStateDoc): SyncStateDoc {
        val now = Date.now()
        val self = DeviceId.get()
        var seeded = start
        for (name in model.allCanvasNames()) {
            val canvas = model.getCanvas(name) ?: continue
            // Seed the manifest's STRUCTURE vector too, so an already-shaped local board is resolved
            // by causality on first connect (and a never-edited remote can't silently win).
            val manifestPath = SyncPaths.manifestPath(root(), name)
            val me = seeded.entries[manifestPath]
            if ((me?.vv ?: emptyMap()).isEmpty()) {
                seeded = seeded.with(manifestPath, (me ?: SyncEntry(rev = null, localUpdatedAt = now))
                    .copy(vv = mapOf(self to 1L)))
            }
            for (col in canvas.columns) {
                if (model.getCanvasColumnRaw(name, col) == null) continue
                val colPath = SyncPaths.columnPath(root(), name, col)
                val e = seeded.entries[colPath]
                if ((e?.vv ?: emptyMap()).isEmpty()) {
                    seeded = seeded.with(colPath, (e ?: SyncEntry(rev = null, localUpdatedAt = now))
                        .copy(vv = mapOf(self to 1L)))
                }
            }
        }
        return seeded
    }

    private suspend fun pushAllLocal(eng: SyncEngine, start: SyncStateDoc): SyncStateDoc {
        val now = Date.now()
        // Defensive: seeding normally already happened before the pull in run(); this catches any
        // column that gained content between pull and push. Idempotent for already-seeded columns.
        val seeded = seedLocalVvs(start)
        val items = mutableListOf<PushItem>()
        for (name in model.allCanvasNames()) {
            val canvas = model.getCanvas(name) ?: continue
            val manifestPath = SyncPaths.manifestPath(root(), name)
            items += PushItem(manifestPath, adapter.manifestContent(name, seeded) ?: continue,
                seeded.entries[manifestPath]?.localUpdatedAt ?: now)
            for (col in canvas.columns) {
                val content = model.getCanvasColumnRaw(name, col) ?: continue   // layout cols have none
                val colPath = SyncPaths.columnPath(root(), name, col)
                items += PushItem(colPath, content, seeded.entries[colPath]?.localUpdatedAt ?: now)
            }
        }
        val (s, conflicts) = eng.pushBatch(items, seeded)   // all files upload concurrently
        conflicts.forEach { (path, winner) -> eventBus.fire(SyncConflict(path, winner)) }
        return s
    }

    /**
     * True when this device holds real board content (more than a blank default board) — so the modal
     * offers the FROM/TO choice. When false, FROM is the obvious default (there's nothing local to
     * assert), and a single "Connect" suffices.
     */
    fun hasLocalContent(): Boolean = model.allCanvasNames().any { name ->
        val canvas = model.getCanvas(name) ?: return@any false
        canvas.columns.any { col -> !model.getCanvasColumnRaw(name, col).isNullOrBlank() }
    }
}
