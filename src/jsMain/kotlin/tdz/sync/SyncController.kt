package tdz.sync

import kotlinx.browser.window as browserWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.js.Date
import tdz.controller.events.ColumnEdited
import tdz.controller.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.RefreshColumns
import tdz.controller.events.SyncConflict
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.Model
import tdz.model.localstorage.CanvasDoc
import tdz.model.localstorage.Namespace
import tdz.model.localstorage.SyncEntry
import tdz.model.localstorage.SyncStateDoc
import tdz.sync.local.BrowserFsTree
import tdz.sync.local.HandleStore
import tdz.sync.local.LocalFolderStorage
import kotlinx.coroutines.await
import web.events.EventType
import web.events.addEventListener
import web.window.window

/**
 * Wires the Dropbox sync subsystem to the [EventBus] in parallel with [tdz.controller.Controller].
 * Owns the [SyncEngine] lifecycle (rebuilt on connect/disconnect), debounced pushes, and the
 * reconcile triggers (startup + reconnect + manual). The only writer of [SyncStateStore].
 */
class SyncController(
    private val model: Model,
    private val eventBus: EventBus,
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var engine: SyncEngine? = null
    private var localHandle: dynamic = null   // the granted FileSystemDirectoryHandle, once loaded/picked
    private val pushTimers = mutableMapOf<String, Int>()   // path -> window timeout handle
    private val PUSH_DEBOUNCE_MS = 1500

    // True while a pull is applying remote changes. A pulled manifest fires RefreshCanvas (so the UI
    // re-renders), but onCanvasStructure also listens to RefreshCanvas — without this guard a pull
    // would immediately re-push what it just pulled (redundant write + cross-device rev churn).
    private var reconciling = false

    private fun config() = model.readSyncConfig()
    private fun state() = model.readSyncState()
    private fun saveState(s: SyncStateDoc) = model.writeSyncState(s)
    // Normalized path prefix ("" or "/folder") — never the raw config string, which would be an
    // invalid Dropbox list_folder path if non-empty and missing a leading slash.
    private fun root() = SyncPaths.rootPrefix(config().rootFolder)

    init {
        eventBus.on<ColumnEdited> { e -> onColumnEdited(e) }
        eventBus.on<RefreshCanvas> { e -> if (e.structural) onCanvasStructure(e.canvasName) }
        val onlineHandler: (web.events.Event) -> Unit = { reconcile() }
        val offlineHandler: (web.events.Event) -> Unit = {
            if (config().enabled) SyncStateStore.set(SyncState.Offline, eventBus)
        }
        window.addEventListener(EventType("online"), onlineHandler)
        window.addEventListener(EventType("offline"), offlineHandler)
    }

    /** Build the engine from current config; call after connect / on startup. */
    fun start() {
        val cfg = config()
        if (!cfg.enabled) {
            SyncStateStore.set(SyncState.NotConfigured, eventBus)
            engine = null
            return
        }
        if (cfg.provider == SyncProvider.LOCAL_FOLDER) { startLocalFolder(); return }
        val storage = SyncProviders.storage(cfg)
        if (storage == null) {
            SyncStateStore.set(SyncState.NotConfigured, eventBus)
            engine = null
            return
        }
        engine = buildEngine(storage)
        reconcile()
    }

    /**
     * Startup path for a configured local folder: load the stored handle, then check permission WITHOUT
     * prompting (queryPermission needs no gesture). Granted -> sync. Otherwise stash the handle and show
     * NeedsReconnect; the user re-grants via a gesture ([reconnectLocalFolder]).
     */
    private fun startLocalFolder() {
        scope.launch {
            val handle = runCatching { HandleStore.load() }.getOrNull()
            if (handle == null) {
                SyncStateStore.set(SyncState.NotConfigured, eventBus)
                engine = null
                return@launch
            }
            localHandle = handle
            val perm = runCatching { HandleStore.queryPermission(handle) }.getOrDefault("prompt")
            if (perm == "granted") {
                engine = buildEngine(LocalFolderStorage(BrowserFsTree(handle)))
                reconcile()
            } else {
                engine = null
                SyncStateStore.set(SyncState.NeedsReconnect, eventBus)
            }
        }
    }

    private fun buildEngine(storage: RemoteStorage) = SyncEngine(
        storage = storage,
        readLocal = { path -> readLocal(path) },
        writeLocal = { path, content -> writeLocal(path, content) },
        deleteLocal = { path -> deleteLocal(path) },
    )

    // ---- path <-> Model translation ----

    private fun readLocal(path: String): String? {
        SyncPaths.parseColumn(path)?.let { (canvas, col) ->
            return model.getCanvasColumnRaw(canvas, col)
        }
        if (SyncPaths.isManifest(path)) {
            val canvas = manifestCanvas(path) ?: return null
            val c = model.getCanvas(canvas) ?: return null
            return JSON.stringify(CanvasDoc(c.name, c.parent, c.columns, c.layouts).toJson())
        }
        return null
    }

    private fun writeLocal(path: String, content: String) {
        SyncPaths.parseColumn(path)?.let { (canvas, col) ->
            model.updateColumn(canvas, col, content)
            eventBus.fire(RefreshColumns(canvas, setOf(col)))
            return
        }
        if (SyncPaths.isManifest(path)) {
            val doc = CanvasDoc.fromJson(JSON.parse(content))
            model.addCanvas(Canvas(doc.name, doc.parentName, doc.columns, doc.layouts))
            eventBus.fire(RefreshCanvas(doc.name))
        }
    }

    private fun deleteLocal(path: String) {
        SyncPaths.parseColumn(path)?.let { (canvas, col) ->
            model.deleteColumn(canvas, col)
            eventBus.fire(RefreshColumns(canvas, setOf(col)))
        }
    }

    private fun manifestCanvas(path: String): CanvasName? {
        val folder = path.removeSuffix("/${SyncPaths.MANIFEST}")
        val name = folder.substringAfterLast('/')
        return if (name.isEmpty()) null else CanvasName(name)
    }

    /**
     * Serialized manifest WITH its version-vector section attached from [s]. The single source of truth
     * for manifest bytes on every push path — never serialize a manifest any other way (a plain
     * CanvasDoc.toJson() drops `vv` and would wipe the vectors). Null if the canvas no longer exists.
     */
    private fun manifestContent(name: CanvasName, s: SyncStateDoc): String? {
        val canvas = model.getCanvas(name) ?: return null
        val j = CanvasDoc(canvas.name, canvas.parent, canvas.columns, canvas.layouts).toJson()
        ManifestVv.write(j, root(), name, s.entries.mapValues { it.value.vv })
        return JSON.stringify(j)
    }

    // ---- push side ----

    private fun onColumnEdited(e: ColumnEdited) {
        val eng = engine ?: return
        val path = SyncPaths.columnPath(root(), e.canvasName, e.columnId)
        val now = Date.now()
        val self = DeviceId.get()
        val prev = state().entries[path]
        val stamped = (prev ?: SyncEntry(rev = null, localUpdatedAt = now))
            .copy(localUpdatedAt = now, vv = bump(prev?.vv ?: emptyMap(), self))
        saveState(state().with(path, stamped))
        debouncePush(path) {
            SyncStateStore.set(SyncState.Syncing, eventBus)
            // Push the persisted column bytes (which include the column's style header that Controller
            // preserves), not the raw editor markdown which omits it.
            val content = model.getCanvasColumnRaw(e.canvasName, e.columnId) ?: e.markdown
            val (newState, winner) = eng.push(path, content, now, state())
            saveState(newState)
            // Re-push the manifest so the remote carries this column's updated version vector. If this
            // fails (the column already uploaded, so the remote vector would otherwise lag the column),
            // mark the manifest pending (rev = null) so the next reconcile retries it with the current vv.
            val manifestPath = SyncPaths.manifestPath(root(), e.canvasName)
            runCatching {
                manifestContent(e.canvasName, state())?.let { mc ->
                    saveState(eng.push(manifestPath, mc, now, state()).first)
                }
            }.onFailure { t ->
                console.warn("[sync] manifest vv push failed; marking pending for retry: ${t.message}")
                val cur = state().entries[manifestPath]
                saveState(state().with(manifestPath,
                    (cur ?: SyncEntry(rev = null, localUpdatedAt = now)).copy(rev = null)))
            }
            if (winner == Winner.LOCAL) eventBus.fire(SyncConflict(path, "local"))
            if (winner == Winner.REMOTE) eventBus.fire(SyncConflict(path, "remote"))
            SyncStateStore.set(SyncState.Synced, eventBus)
        }
    }

    private fun onCanvasStructure(canvasName: CanvasName) {
        val eng = engine ?: return
        if (reconciling) return   // this RefreshCanvas came from a pull importing a manifest — don't echo
        val canvas = model.getCanvas(canvasName) ?: return
        scope.launch {
            SyncStateStore.set(SyncState.Syncing, eventBus)
            // push manifest
            val manifestPath = SyncPaths.manifestPath(root(), canvasName)
            var s = state()
            val manifest = manifestContent(canvasName, s) ?: return@launch
            s = eng.push(manifestPath, manifest, Date.now(), s).first
            // reconcile column files: delete orphans not in canvas.columns (concurrently)
            val live = canvas.columns.map { SyncPaths.columnPath(root(), canvasName, it) }.toSet()
            val folder = SyncPaths.canvasFolder(root(), canvasName)
            val orphans = s.entries.keys.filter {
                it.startsWith("$folder/") && SyncPaths.parseColumn(it) != null && it !in live
            }
            if (orphans.isNotEmpty()) s = eng.deleteRemoteBatch(orphans, s)
            saveState(s)
            SyncStateStore.set(SyncState.Synced, eventBus)
        }
    }

    private fun debouncePush(path: String, block: suspend () -> Unit) {
        pushTimers[path]?.let { browserWindow.clearTimeout(it) }
        pushTimers[path] = browserWindow.setTimeout({
            pushTimers.remove(path)
            scope.launch { runCatching { block() }.onFailure { fail(it) } }
        }, PUSH_DEBOUNCE_MS)
    }

    // ---- pull / reconcile ----

    fun reconcile() {
        val eng = engine ?: return
        if (reconciling) return   // one pull in flight is enough; also keeps the echo guard stable
        if (window.asDynamic().navigator.onLine == false) {
            SyncStateStore.set(SyncState.Offline, eventBus)
            return
        }
        reconciling = true        // set synchronously so a rapid second call is rejected above
        scope.launch {
            LocalBackup.snapshot()   // recoverable safety net before any pull can overwrite/delete local
            SyncStateStore.set(SyncState.Reconciling, eventBus)
            runCatching {
                val result = eng.pull(root(), state())
                var s = result.state
                console.log("[sync] pull: ${result.changedPaths.size} changed, ${result.keptLocalPaths.size} kept-local")
                // Two-way reconcile: push files the remote doesn't have yet (pending, rev == null) and
                // those where local won last-write-wins. Without this, edits made while pushes were
                // failing (e.g. an expired Drive token) would never reach the remote on a plain Sync now.
                val pending = s.entries.filter { (p, e) -> e.rev == null && readLocal(p) != null }.keys
                val dirty = (pending + result.keptLocalPaths).distinct()
                val items = dirty.mapNotNull { p ->
                    // Manifests must be serialized WITH their vv (readLocal rebuilds them from the model
                    // and would drop the vectors); columns use their raw local bytes.
                    val content = if (SyncPaths.isManifest(p)) manifestCanvas(p)?.let { manifestContent(it, s) }
                                  else readLocal(p)
                    content?.let { PushItem(p, it, s.entries[p]?.localUpdatedAt ?: Date.now()) }
                }
                if (items.isNotEmpty()) {
                    console.log("[sync] reconcile pushing ${items.size} pending/local-newer file(s)")
                    val (pushed, conflicts) = eng.pushBatch(items, s)
                    s = pushed
                    conflicts.forEach { (path, w) -> fireConflict(path, w) }
                }
                saveState(s)
                // writeLocal already fired refresh events per changed file
                SyncStateStore.set(SyncState.Synced, eventBus)
            }.onFailure { fail(it) }
            reconciling = false
        }
    }

    private fun fail(t: Throwable) {
        val state = syncFailureState(t)
        if (state is SyncState.Error) console.error("[sync] error: ${t.message}")
        else console.warn("[sync] needs reconnect: ${t.message}")
        SyncStateStore.set(state, eventBus)
    }

    // ---- modal-facing API ----

    fun setEnabled(enabled: Boolean) {
        model.writeSyncConfig(config().copy(enabled = enabled))
        start()
    }

    /** Persist the chosen provider before connecting (the modal calls this on selection). */
    fun setProvider(provider: SyncProvider) {
        if (provider == config().provider) return
        model.writeSyncConfig(config().copy(provider = provider))
    }

    fun connect() {
        SyncStateStore.set(SyncState.Connecting, eventBus)
        // Fresh connect: drop any stale per-path bookkeeping from a prior provider/session so the first
        // pull cannot mistake previously-tracked paths for remote deletions. initialSync re-establishes
        // revs by pulling then pushing all local content.
        saveState(SyncStateDoc.EMPTY)
        val provider = config().provider
        scope.launch {
            runCatching { SyncProviders.connect(provider) }
                .onSuccess { creds ->
                    // Blank root means "use the provider default" (Drive needs a concrete base folder).
                    val cfg = config()
                    val newCfg = cfg.copy(
                        enabled = true,
                        rootFolder = cfg.rootFolder.ifBlank { SyncProviders.defaultRoot(provider) },
                        refreshToken = creds.refreshToken,
                        accountEmail = creds.accountEmail,
                        localFolderName = null,
                    )
                    model.writeSyncConfig(newCfg)
                    val storage = SyncProviders.storage(newCfg)
                    if (storage == null) { fail(Exception("Connect produced no storage")); return@onSuccess }
                    engine = buildEngine(storage)
                    initialSync()
                }
                .onFailure { fail(it) }
        }
    }

    /**
     * Pick a local folder and start syncing to it. MUST be invoked directly from a user gesture (a menu
     * button click): the picker Promise is taken synchronously so the call stays inside user activation.
     */
    fun connectLocalFolder() {
        val promise = runCatching { HandleStore.pickDirectory() }.getOrElse { fail(it); return }
        SyncStateStore.set(SyncState.Connecting, eventBus)
        scope.launch {
            runCatching {
                val handle = promise.await()                       // picker grants read-write here
                HandleStore.save(handle)
                localHandle = handle
                val name = (handle.name as? String) ?: "folder"
                model.writeSyncConfig(config().copy(
                    provider = SyncProvider.LOCAL_FOLDER,
                    enabled = true,
                    rootFolder = "",
                    refreshToken = null,
                    accountEmail = null,
                    localFolderName = name,
                ))
                engine = buildEngine(LocalFolderStorage(BrowserFsTree(handle)))
                initialSync()
            }.onFailure { e ->
                // AbortError = user dismissed the picker: return to a calm state, not an error toast.
                if (isAbort(e)) {
                    val cfg = config()
                    SyncStateStore.set(
                        if (cfg.localConnected) SyncState.NeedsReconnect else SyncState.NotConfigured,
                        eventBus,
                    )
                } else fail(e)
            }
        }
    }

    /**
     * Re-request permission on the stored handle and resume. MUST be invoked from a user gesture. Falls
     * back to a fresh pick if no handle is in memory.
     */
    fun reconnectLocalFolder() {
        val handle = localHandle ?: run { connectLocalFolder(); return }
        val promise = HandleStore.requestPermissionPromise(handle)
        scope.launch {
            val perm = runCatching { promise.await() }.getOrDefault("denied")
            if (perm == "granted") {
                engine = buildEngine(LocalFolderStorage(BrowserFsTree(handle)))
                reconcile()
            } else {
                SyncStateStore.set(SyncState.NeedsReconnect, eventBus)
            }
        }
    }

    private fun isAbort(e: Throwable): Boolean = (e.message ?: "").contains("abort", ignoreCase = true)

    /**
     * First-connect two-way sync: pull remote into local, then push EVERY local canvas (manifest +
     * columns) up. Without this, connecting with existing local data leaves Dropbox empty until each
     * column is next edited. Per-file LWW still applies via [SyncEngine.push]; for files imported by
     * the pull, the re-push is redundant-but-harmless. Runs locked (Reconciling) like a reconcile.
     */
    private suspend fun initialSync() {
        val eng = engine ?: return
        SyncStateStore.set(SyncState.Reconciling, eventBus)
        reconciling = true
        LocalBackup.snapshot()   // recoverable safety net before any pull can overwrite/delete local
        runCatching {
            // Seed local column vectors BEFORE pulling: otherwise an unseeded local column would be
            // silently overwritten by a remote that another device already seeded (REMOTE_DOMINATES).
            var s = seedLocalVvs(state())
            saveState(s)
            s = eng.pull(root(), s).state               // remote -> local
            s = pushAllLocal(eng, s)                    // local -> remote (manifests + columns)
            saveState(s)
            SyncStateStore.set(SyncState.Synced, eventBus)
        }.onFailure { fail(it) }
        reconciling = false
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
        for (name in allLocalCanvases()) {
            val canvas = model.getCanvas(name) ?: continue
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
        // Defensive: seeding normally already happened before the pull in initialSync; this catches any
        // column that gained content between pull and push. Idempotent for already-seeded columns.
        val seeded = seedLocalVvs(start)
        val items = mutableListOf<PushItem>()
        for (name in allLocalCanvases()) {
            val canvas = model.getCanvas(name) ?: continue
            val manifestPath = SyncPaths.manifestPath(root(), name)
            items += PushItem(manifestPath, manifestContent(name, seeded) ?: continue,
                seeded.entries[manifestPath]?.localUpdatedAt ?: now)
            for (col in canvas.columns) {
                val content = model.getCanvasColumnRaw(name, col) ?: continue   // layout cols have none
                val colPath = SyncPaths.columnPath(root(), name, col)
                items += PushItem(colPath, content, seeded.entries[colPath]?.localUpdatedAt ?: now)
            }
        }
        val (s, conflicts) = eng.pushBatch(items, seeded)   // all files upload concurrently
        conflicts.forEach { (path, winner) -> fireConflict(path, winner) }
        return s
    }

    private fun fireConflict(path: String, winner: Winner) =
        eventBus.fire(SyncConflict(path, if (winner == Winner.LOCAL) "local" else "remote"))

    /**
     * Enumerate local canvases by scanning the NEWCANVAS namespace in localStorage. Done here (rather
     * than via Model) to keep this work inside the sync vertical while the shared storage layer is
     * being edited by another agent. Keys are `<namespace><canvasName.normalized()>`.
     */
    private fun allLocalCanvases(): List<CanvasName> {
        val prefix = Namespace.NEWCANVAS.toString()
        val ls = browserWindow.localStorage
        val out = mutableListOf<CanvasName>()
        for (i in 0 until ls.length) {
            val k = ls.key(i) ?: continue
            if (k.startsWith(prefix)) out.add(CanvasName(k.removePrefix(prefix)))
        }
        return out
    }

    fun disconnect() {
        val cfg = config()
        if (cfg.provider == SyncProvider.LOCAL_FOLDER) {
            // No token to revoke; forget the handle so a later connect re-picks.
            scope.launch { runCatching { HandleStore.clear() } }
            localHandle = null
        } else {
            // Revoke on the provider (best-effort, async) so a later Connect re-prompts for consent.
            SyncProviders.storage(cfg)?.let { storage ->
                scope.launch { runCatching { storage.revoke() } }
            }
        }
        // Cancel any pending debounced pushes.
        pushTimers.values.forEach { browserWindow.clearTimeout(it) }
        pushTimers.clear()
        // Drop credentials AND the local sync bookkeeping (revs/timestamps), keeping the folder setting.
        // Clearing sync state means the next connect re-imports cleanly instead of treating remote files
        // as deletions.
        model.writeSyncConfig(cfg.copy(
            enabled = false, refreshToken = null, accountEmail = null, localFolderName = null,
        ))
        saveState(SyncStateDoc.EMPTY)
        engine = null
        SyncStateStore.set(SyncState.NotConfigured, eventBus)
    }

    fun setRootFolder(folder: String) {
        model.writeSyncConfig(config().copy(rootFolder = folder))
    }

    /**
     * Switch the sync root while connected: persist the new folder, drop the old root's bookkeeping
     * (its revs/timestamps don't apply to the new path), and re-sync against it (pull what's there,
     * push all local up). Old-root files are left on the remote, not moved.
     */
    fun changeRootFolder(folder: String) {
        if (folder == config().rootFolder) return
        model.writeSyncConfig(config().copy(rootFolder = folder))
        saveState(SyncStateDoc.EMPTY)
        if (engine != null) scope.launch { initialSync() }
    }

    fun syncNow() = reconcile()

    /** User-triggered recovery: restore the most recent pre-sync snapshot and re-render. Returns
     *  false if there was no backup to restore. */
    fun restoreLastBackup(): Boolean {
        val id = LocalBackup.latest() ?: return false
        val ok = LocalBackup.restore(id)
        if (ok) eventBus.fire(RefreshCanvas(model.currentCanvas().name))
        return ok
    }
}
