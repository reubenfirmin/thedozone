package tdz.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.js.Date
import tdz.controller.events.ColumnsChanged
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.SyncConflict
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.Model
import tdz.model.localstorage.CanvasDoc
import tdz.model.localstorage.CanvasStorageKeys
import tdz.model.localstorage.SyncEntry
import tdz.model.localstorage.SyncStateDoc
import web.events.EventType
import web.events.addEventListener
import web.window.window

/**
 * Wires the sync subsystem to the [EventBus] in parallel with [tdz.controller.Controller]. Thin: it
 * owns the single [CoroutineScope] and the shared [SyncSession] (the live [SyncEngine] + the
 * reconcile gate), the EventBus subscription with echo-loop protection, the periodic/manual
 * [reconcile] pull, and the [restoreAutoBackup] recovery. The connect/disconnect/reconnect LIFECYCLE,
 * the FROM/TO initial-sync flows, and the modal-facing provider/enabled/root mutators live in
 * [ConnectionLifecycle] (which owns [InitialSync]); the facade methods below delegate to it.
 * Collaborators: [SyncStateAccess] (config/state reads), [ModelPathAdapter] (path<->model translation
 * + push content), [PushScheduler] (debounced pushes), [ConnectionLifecycle], and [SyncEngine] (the
 * merge/deletion POLICY). The only writer of [SyncStateStore].
 */
class SyncController(
    private val model: Model,
    private val eventBus: EventBus<Event>,
    /** Test seam: bypasses [SyncProviders.storage] so suites can run against an in-memory remote. */
    private val storageOverride: RemoteStorage? = null,
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    // Shared with ConnectionLifecycle: the live engine and the reconcile gate both coordinate on.
    private val session = SyncSession()
    private val access = SyncStateAccess(model)
    internal var pushDebounceMs = 1500   // quiet period after a local edit before pushing

    private fun state() = access.state()
    private fun saveState(s: SyncStateDoc) = access.saveState(s)
    private fun root() = access.root()

    // Translation between remote paths and the model; the engine's read/write/delete callbacks and the
    // single producer of push content (manifests always carry their vv).
    private val adapter = ModelPathAdapter(model, eventBus, ::root, ::state)

    // The local facts the engine's deletion policy asks for — it never reads the model directly.
    private val facts = object : LocalFacts {
        override fun canvasExists(canvas: CanvasName) = model.getCanvas(canvas) != null
        override fun columnLive(canvas: CanvasName, col: ColumnId) =
            model.getCanvas(canvas)?.let { col in it.columns } ?: false
    }

    private val pushScheduler = PushScheduler(
        scope = scope, eventBus = eventBus, adapter = adapter, facts = facts,
        engine = { session.engine }, readState = ::state, saveState = ::saveState, root = ::root,
        debounceMs = { pushDebounceMs }, onFailure = { fail(it) },
    )

    // Owns the connect/disconnect/reconnect lifecycle and the initial-sync flows; shares state via
    // [session]/[access]. [reconcile] is the one back-reference (the controller owns the pull loop).
    private val connection = ConnectionLifecycle(
        model, eventBus, scope, session, adapter, access, storageOverride, pushScheduler,
        reconcile = ::reconcile,
    )

    // The pure, pre-connect config writes (provider/root); no lifecycle coupling.
    private val configMutator = SyncConfigMutator(model, access)

    /** Test seam: bypasses the provider's interactive consent popup in suites. */
    internal var connectOverride: (suspend (SyncProvider) -> ConnectResult)?
        get() = connection.connectOverride
        set(value) { connection.connectOverride = value }

    private fun fail(t: Throwable): Unit = connection.fail(t)

    init {
        // ColumnsChanged is the model fact every local mutation emits (editor save, task toggle,
        // clear-done, drag reorder, wormhole move) — the single hook that keeps devices in sync.
        // remote=true marks changes this controller itself just pulled; pushing those back would
        // be an echo loop.
        eventBus.on<ColumnsChanged> { e ->
            if (!e.remote) e.columnIds.forEach { pushScheduler.onColumnChanged(e.canvasName, it) }
        }
        eventBus.on<RefreshCanvas> { e -> if (e.structural && !e.remote) pushScheduler.onCanvasStructure(e.canvasName) }
        val onlineHandler: (web.events.Event) -> Unit = { reconcile() }
        val offlineHandler: (web.events.Event) -> Unit = {
            if (access.config().enabled) SyncStateStore.set(SyncState.Offline, eventBus)
        }
        window.addEventListener(EventType("online"), onlineHandler)
        window.addEventListener(EventType("offline"), offlineHandler)
    }

    /**
     * First-sync direction the user picks when connecting to a folder that already has content:
     * - [FROM] "the folder is the source of truth": pull + MERGE (causal union; nothing lost). The
     *   sensible default when this device has nothing (or very little) of its own.
     * - [TO] "this device is correct": make the remote MIRROR local — force-push every local file and
     *   delete any remote file not present locally. NO merge.
     * When the remote is empty the two converge (both just push local up), so the choice is moot.
     */
    enum class InitialSyncMode { FROM, TO }

    // ---- connection lifecycle (delegates to ConnectionLifecycle) ----

    /** Build the engine from current config; call after connect / on startup. */
    fun start() = connection.start()

    fun connect(mode: InitialSyncMode? = null) = connection.connect(mode)

    /** The user's answer to [SyncState.ChoosingDirection] (the modal calls this). */
    fun chooseInitialSync(mode: InitialSyncMode) = connection.chooseInitialSync(mode)

    fun reconnect() = connection.reconnect()

    fun connectLocalFolder() = connection.connectLocalFolder()

    fun reconnectLocalFolder() = connection.reconnectLocalFolder()

    fun disconnect() = connection.disconnect()

    /** Persist the chosen provider before connecting (the modal calls this on selection). */
    fun setProvider(provider: SyncProvider) = configMutator.setProvider(provider)

    fun setEnabled(enabled: Boolean) = connection.setEnabled(enabled)

    fun setRootFolder(folder: String) = configMutator.setRootFolder(folder)

    fun changeRootFolder(folder: String) = connection.changeRootFolder(folder)

    /**
     * True when this device holds real board content (more than a blank default board) — so the modal
     * offers the FROM/TO choice. When false, FROM is the obvious default (there's nothing local to
     * assert), and a single "Connect" suffices.
     */
    fun hasLocalContent(): Boolean = connection.hasLocalContent()

    /** Max tombstones kept per canvas. Tombstones are tiny and opaque ids are NEVER reused, so a
     *  dropped tombstone can only resurrect a column if a device was offline across more than this many
     *  deletions on one board — negligible. This is a growth bound, not a causal GC (a fully causal GC
     *  needs per-device delete-acknowledgement we don't yet track). */
    private val tombstoneCap = 500

    /**
     * Prune the oldest tombstones (by [SyncEntry.localUpdatedAt]) beyond [tombstoneCap] PER CANVAS, so
     * the manifest's `deleted` set and the local state can't grow without bound. Live entries are never
     * touched.
     */
    private fun prunedTombstones(s: SyncStateDoc): SyncStateDoc {
        val tombstones = s.entries.filter { it.value.tombstone }
        if (tombstones.size <= tombstoneCap) return s
        val byCanvas = tombstones.entries.groupBy { SyncPaths.parseColumn(it.key, root())?.first?.normalized() }
        val drop = byCanvas.values.flatMap { group ->
            group.sortedByDescending { it.value.localUpdatedAt }.drop(tombstoneCap)
        }.map { it.key }
        if (drop.isEmpty()) return s
        var out = s
        drop.forEach { out = out.without(it) }
        return out
    }

    // ---- pull / reconcile ----

    fun reconcile() {
        val eng = session.engine ?: return
        if (!web.navigator.navigator.onLine) {
            SyncStateStore.set(SyncState.Offline, eventBus)
            return
        }
        // Auth not in hand (expired Drive token on startup): do NOT attempt remote ops — they'd each
        // fire a blocked auth popup and abort. Bail to NeedsReconnect quietly; the local state
        // (edits AND deletions) stays recorded and propagates on the next reconcile after the user
        // clicks Reconnect (the gesture that's allowed to pop the consent window).
        if (!eng.isReady()) {
            SyncStateStore.set(SyncState.NeedsReconnect, eventBus)
            return
        }
        // Take the shared gate SYNCHRONOUSLY before scope.launch so a rapid second call (or an
        // overlapping initial-sync) observes it taken and bails — one pull in flight is enough, and it
        // keeps the echo guard stable.
        if (!session.tryBeginReconcile()) return
        val t0 = Date.now()
        SyncLog.resetCounters()
        SyncLog.log("reconcile started")
        scope.launch {
            // try/finally, not just runCatching: if ANYTHING here escapes (e.g. fail() itself),
            // a stuck gate would silently freeze every future reconcile this session.
            try {
            SyncStateStore.set(SyncState.Reconciling, eventBus)
            runCatching {
                tdz.model.localstorage.flushPendingWrites()   // buffered edits must be on disk before the pull reads local
                saveState(prunedTombstones(state()))   // bound tombstone growth (see [prunedTombstones])
                // Local deletions must STICK: anything still tracked in state but gone locally was
                // deleted here, and the pull below would otherwise RE-IMPORT it. The engine owns the
                // classification (tombstone a column of a surviving board; drop a whole-board deletion)
                // and propagates it to the remote BEFORE we pull, using local facts we supply.
                saveState(eng.propagateLocalDeletions(root(), state(), facts))
                val tPull = Date.now()
                val result = eng.pull(root(), state())
                var s = result.state
                SyncLog.log("pull: ${result.changedPaths.size} changed, " +
                    "${result.keptLocalPaths.size} kept-local (${SyncLog.since(tPull)})")
                // Two-way reconcile: push files the remote doesn't have yet (pending, rev == null) and
                // those where local won last-write-wins. Without this, edits made while pushes were
                // failing (e.g. an expired Drive token) would never reach the remote on a plain Sync now.
                val pending = s.entries.filter { (p, e) -> e.rev == null && adapter.readLocal(p) != null }.keys
                val dirty = (pending + result.keptLocalPaths).distinct()
                val items = dirty.mapNotNull { p ->
                    // contentFor serializes manifests WITH their vv and columns as raw bytes — the
                    // single producer, so a push can't accidentally drop the vectors.
                    adapter.contentFor(p, s)?.let { PushItem(p, it, s.entries[p]?.localUpdatedAt ?: Date.now()) }
                }
                if (items.isNotEmpty()) {
                    val tPush = Date.now()
                    val (pushed, conflicts) = eng.pushBatch(items, s)
                    s = pushed
                    conflicts.forEach { (path, w) -> eventBus.fire(SyncConflict(path, w)) }
                    SyncLog.log("pushed ${items.size} pending/local-newer file(s) (${SyncLog.since(tPush)})")
                }
                saveState(s)
                // writeLocal already fired refresh events per changed file
                SyncStateStore.set(SyncState.Synced, eventBus)
                SyncLog.log("reconcile finished in ${SyncLog.since(t0)} — ${SyncLog.summary()}")
            }.onFailure {
                SyncLog.log("reconcile FAILED after ${SyncLog.since(t0)} — ${SyncLog.summary()}: ${it.message}")
                fail(it)
            }
            } finally {
                session.endReconcile()
            }
        }
    }

    fun syncNow() = reconcile()

    /**
     * User-triggered recovery: roll the CURRENT board back to the periodic backup
     * ([PeriodicBackup]). Scoped strictly to that canvas — its manifest, columns, and column
     * modes; other boards, the sync bookkeeping, and the theme are untouched. The kept sync
     * vectors are the ones that match/dominate the remote's, so after re-stamping the board's
     * files as pending/newest the reconcile pushes the restored content UP, instead of the next
     * pull "correcting" the rollback with the remote's newer-but-unwanted content. Columns the
     * rollback removed (created after the backup) are DELETED on the remote before that reconcile
     * — the rollback is authoritative; they must not re-import.
     * Returns false when no periodic backup exists or it doesn't contain this board.
     */
    fun restoreAutoBackup(): Boolean {
        val name = model.currentCanvas().name
        val manifestKey = CanvasStorageKeys.manifest(name)
        val backupCanvas = LocalBackup.autoValue(manifestKey)?.let { CanvasDoc.decode(it) }
            ?: return false   // the board didn't exist when the backup was taken
        // Exact keys, from BOTH manifests: backup columns are replayed, columns that only exist
        // now are removed (rollback), and key shapes never rely on name-prefix matching (canvas
        // names may contain each other).
        val currentColumns = model.getCanvas(name)?.columns ?: emptyList()
        val keys = buildSet {
            add(manifestKey)
            for (col in (backupCanvas.columns + currentColumns).distinct()) {
                add(CanvasStorageKeys.column(name, col))
                add(CanvasStorageKeys.columnMode(name, col))
            }
        }
        if (!LocalBackup.restoreAutoKeys(keys)) return false
        stampCanvasAsPending(name)
        model.changeCanvas(name)   // the in-memory canvas predates the rollback — reload it
        eventBus.fire(RefreshCanvas(name, remote = true))
        // Columns the rollback removed must die on the remote BEFORE the reconcile pulls — a
        // listed-but-untracked-locally file would otherwise re-import. Tracked-only paths: a
        // never-synced column has nothing remote to delete.
        val removed = currentColumns.filter { it !in backupCanvas.columns }
            .map { SyncPaths.columnPath(root(), name, it) }
            .filter { it in state().entries }
        scope.launch {
            runCatching {
                val eng = session.engine
                if (removed.isNotEmpty() && eng != null) saveState(eng.deleteRemoteBatch(removed, state()))
            }.onFailure {
                // Remote deletion failed (offline / auth lapsed): do NOT reconcile into a
                // re-import; the local rollback stands and the user can sync once healthy.
                fail(it)
                return@launch
            }
            reconcile()
        }
        return true
    }

    /**
     * Mark one canvas's manifest and columns pending (rev = null, fresh timestamp) and bump each
     * column's version vector, all on top of the CURRENT bookkeeping: this makes the board's
     * local content causally ahead of the remote, so the next reconcile pushes it up (or 3-way
     * merges against genuinely concurrent edits from other devices).
     */
    private fun stampCanvasAsPending(name: CanvasName) {
        val canvas = model.getCanvas(name) ?: return
        val now = Date.now()
        val self = DeviceId.get()
        var s = state()
        val manifestPath = SyncPaths.manifestPath(root(), name)
        val prevManifest = s.entries[manifestPath]
        s = s.with(manifestPath, (prevManifest ?: SyncEntry(rev = null, localUpdatedAt = now))
            .copy(rev = null, localUpdatedAt = now, vv = bump(prevManifest?.vv ?: emptyMap(), self)))
        for (col in canvas.columns) {
            if (model.getCanvasColumnRaw(name, col) == null) continue   // layout cols have no content
            val colPath = SyncPaths.columnPath(root(), name, col)
            val prev = s.entries[colPath]
            s = s.with(colPath, (prev ?: SyncEntry(rev = null, localUpdatedAt = now))
                .copy(rev = null, localUpdatedAt = now, vv = bump(prev?.vv ?: emptyMap(), self)))
        }
        saveState(s)
    }
}
