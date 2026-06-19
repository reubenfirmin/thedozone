package tdz.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import tdz.controller.events.Event
import tdz.model.Model
import tdz.model.localstorage.SyncStateDoc
import tdz.sync.local.BrowserFsTree
import tdz.sync.local.HandleStore
import tdz.sync.local.LocalFolderStorage
import zoned.framework.events.EventBus

private const val DROPBOX_ROOT_MSG =
    "Set a sync folder — Dropbox can't sync to its app-folder root."

/**
 * Owns the connect / disconnect / reconnect LIFECYCLE for the sync subsystem: provider auth, engine
 * (re)build, local-folder picking + permission, disconnect, and the Dropbox-root safeguard. The
 * FROM/TO first-sync logic lives in its own collaborator [InitialSync] (built and owned here, so the
 * direction decision is a direct call, not an injected lambda). Shares the live engine and the
 * reconcile gate through [SyncSession]; [reconcile] is the one genuine back-reference (the controller
 * owns the periodic pull loop). [fail] and the (test-overridable) provider connect are real members
 * here, not back-pointers.
 */
internal class ConnectionLifecycle(
    private val model: Model,
    private val eventBus: EventBus<Event>,
    private val scope: CoroutineScope,
    private val session: SyncSession,
    private val adapter: ModelPathAdapter,
    private val access: SyncStateAccess,
    private val storageOverride: RemoteStorage?,
    private val pushScheduler: PushScheduler,
    private val reconcile: () -> Unit,
) {
    private val initialSync = InitialSync(model, eventBus, scope, session, adapter, access, ::fail)

    private var localHandle: tdz.sync.local.FileSystemDirectoryHandle? = null   // the granted handle, once loaded/picked

    /** Test seam: bypasses the provider's interactive consent popup in suites. */
    var connectOverride: (suspend (SyncProvider) -> ConnectResult)? = null

    private fun config() = access.config()
    private fun state() = access.state()
    private fun saveState(s: SyncStateDoc) = access.saveState(s)
    private fun root() = access.root()

    private suspend fun providerConnect(provider: SyncProvider): ConnectResult =
        connectOverride?.invoke(provider) ?: SyncProviders.connect(provider)

    /** Map a thrown sync failure to a UI state and surface it. */
    fun fail(t: Throwable) {
        val state = syncFailureState(t)
        if (state is SyncState.Error) console.error("[sync] error: ${t.message}")
        else console.warn("[sync] needs reconnect: ${t.message}")
        SyncStateStore.set(state, eventBus)
    }

    /** Build the engine from current config; call after connect / on startup. */
    fun start() {
        val cfg = config()
        if (!cfg.enabled) {
            SyncStateStore.set(SyncState.NotConfigured, eventBus)
            session.engine = null
            return
        }
        if (cfg.provider == SyncProvider.LOCAL_FOLDER) { startLocalFolder(); return }
        if (dropboxRootForbidden(cfg.provider, cfg.rootFolder)) { failRootFolder(); return }
        val storage = storageOverride ?: SyncProviders.storage(cfg)
        if (storage == null) {
            SyncStateStore.set(SyncState.NotConfigured, eventBus)
            session.engine = null
            return
        }
        session.engine = buildEngine(storage)
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
                session.engine = null
                return@launch
            }
            localHandle = handle
            val perm = runCatching { HandleStore.queryPermission(handle) }.getOrDefault("prompt")
            if (perm == "granted") {
                session.engine = buildEngine(LocalFolderStorage(BrowserFsTree(handle)))
                reconcile()
            } else {
                session.engine = null
                SyncStateStore.set(SyncState.NeedsReconnect, eventBus)
            }
        }
    }

    private fun buildEngine(storage: RemoteStorage) = SyncEngine(
        // Instrumented: every remote call is timed and tallied (SyncLog.summary) so a slow sync
        // says where its time went instead of just "Syncing…".
        storage = InstrumentedStorage(storage),
        readLocal = { path -> adapter.readLocal(path) },
        writeLocal = { path, content -> adapter.writeLocal(path, content) },
        deleteLocal = { path -> adapter.deleteLocal(path) },
    )

    /**
     * SAFEGUARD: Dropbox's app folder treats a blank sync folder as its ROOT, so syncing there
     * scatters every board into one shared namespace and lets independent instances/devices collide
     * (the reported app-root litter + cross-board clobbering). Dropbox therefore MUST have a concrete
     * folder. Drive is exempt — it always resolves to a concrete OAuth-scoped folder, never a shared
     * root. [rootFolder] is the raw config value; [SyncPaths.rootPrefix] is empty only when it's blank.
     */
    private fun dropboxRootForbidden(provider: SyncProvider, rootFolder: String): Boolean =
        provider == SyncProvider.DROPBOX && SyncPaths.rootPrefix(rootFolder).isEmpty()

    /** Refuse to sync (no engine, surface the fix) when a cloud root resolves to a forbidden root. */
    private fun failRootFolder() {
        session.engine = null
        console.error("[sync] $DROPBOX_ROOT_MSG")
        SyncStateStore.set(SyncState.Error(DROPBOX_ROOT_MSG), eventBus)
    }

    /**
     * Connect and run the first sync. [mode] forces a direction (used by tests / an explicit
     * re-choice); when null, [InitialSync.decide] picks: remote empty → TO; a brand-new device (no
     * content AND no history) → FROM; otherwise it parks in [SyncState.ChoosingDirection] and the
     * modal asks the user.
     */
    fun connect(mode: SyncController.InitialSyncMode? = null) {
        SyncStateStore.set(SyncState.Connecting, eventBus)
        // "History" = has this device synced this folder before? The bookkeeping survives a disconnect,
        // so a non-empty state means yes. Capture it BEFORE wiping (the chosen direction re-establishes
        // revs from a clean slate).
        val hadHistory = state().entries.isNotEmpty()
        saveState(SyncStateDoc.EMPTY)
        val provider = config().provider
        scope.launch {
            runCatching { providerConnect(provider) }
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
                    // Refuse to connect Dropbox to its app-folder ROOT (blank folder): it scatters
                    // every board into one shared namespace and collides across instances/devices.
                    if (dropboxRootForbidden(newCfg.provider, newCfg.rootFolder)) { failRootFolder(); return@onSuccess }
                    model.writeSyncConfig(newCfg)
                    val storage = storageOverride ?: SyncProviders.storage(newCfg)
                    if (storage == null) { fail(Exception("Connect produced no storage")); return@onSuccess }
                    session.engine = buildEngine(storage)
                    when (mode) {
                        SyncController.InitialSyncMode.FROM -> initialSync.run()
                        SyncController.InitialSyncMode.TO -> initialSync.runTo()
                        null -> initialSync.decide(hadHistory)
                    }
                }
                .onFailure { fail(it) }
        }
    }

    /** The user's answer to [SyncState.ChoosingDirection] (the modal calls this). */
    fun chooseInitialSync(mode: SyncController.InitialSyncMode) = initialSync.choose(mode)

    /**
     * Re-authorize an ALREADY-configured cloud sync and resume. Unlike [connect] this PRESERVES
     * the local sync bookkeeping: the fresh-connect wipe (correct for a new provider/folder)
     * discards the version vectors stamped on edits made while auth was lapsed, so the post-auth
     * pull judged the remote causally ahead and silently reverted those edits. With the state
     * kept, a plain [reconcile] pushes the pending/local-newer files up instead.
     */
    fun reconnect() {
        val cfg = config()
        if (cfg.provider == SyncProvider.LOCAL_FOLDER) { reconnectLocalFolder(); return }
        if (!cfg.cloudConnected) { connect(); return }   // never finished a first connect
        if (dropboxRootForbidden(cfg.provider, cfg.rootFolder)) { failRootFolder(); return }
        SyncStateStore.set(SyncState.Connecting, eventBus)
        scope.launch {
            runCatching { providerConnect(cfg.provider) }
                .onSuccess { creds ->
                    // Keep existing credentials when the provider returns none (Drive has no
                    // refresh token; its in-memory access token was just re-minted by connect).
                    model.writeSyncConfig(config().copy(
                        refreshToken = creds.refreshToken ?: config().refreshToken,
                        accountEmail = creds.accountEmail ?: config().accountEmail,
                    ))
                    val storage = storageOverride ?: SyncProviders.storage(config())
                    if (storage == null) { fail(Exception("Reconnect produced no storage")); return@onSuccess }
                    session.engine = buildEngine(storage)
                    reconcile()
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
                val name = handle.name
                model.writeSyncConfig(config().copy(
                    provider = SyncProvider.LOCAL_FOLDER,
                    enabled = true,
                    rootFolder = "",
                    refreshToken = null,
                    accountEmail = null,
                    localFolderName = name,
                ))
                session.engine = buildEngine(LocalFolderStorage(BrowserFsTree(handle)))
                initialSync.run()
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
                session.engine = buildEngine(LocalFolderStorage(BrowserFsTree(handle)))
                reconcile()
            } else {
                SyncStateStore.set(SyncState.NeedsReconnect, eventBus)
            }
        }
    }

    private fun isAbort(e: Throwable): Boolean = (e.message ?: "").contains("abort", ignoreCase = true)

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
        pushScheduler.cancelAll()
        // Drop credentials but KEEP the sync bookkeeping (revs/vectors): disconnect is a PAUSE. The
        // kept state is this device's "has synced before" HISTORY — connect() reads it to decide
        // whether a content-less device may auto-merge FROM the folder or must be asked. (connect()
        // wipes it before the chosen direction runs; switching provider/folder wipes it too.)
        model.writeSyncConfig(cfg.copy(
            enabled = false, refreshToken = null, accountEmail = null, localFolderName = null,
        ))
        session.engine = null
        SyncStateStore.set(SyncState.NotConfigured, eventBus)
    }

    // ---- lifecycle-coupled config writes ----

    /**
     * Switch the sync root while connected: persist the new folder, drop the old root's bookkeeping
     * (its revs/timestamps don't apply to the new path), and re-sync against it (pull what's there,
     * push all local up). Old-root files are left on the remote, not moved.
     */
    fun changeRootFolder(folder: String) {
        if (folder == config().rootFolder) return
        model.writeSyncConfig(config().copy(rootFolder = folder))
        saveState(SyncStateDoc.EMPTY)
        if (session.engine != null) scope.launch { initialSync.run() }
    }

    fun setEnabled(enabled: Boolean) {
        model.writeSyncConfig(config().copy(enabled = enabled))
        start()
    }

    /** See [InitialSync.hasLocalContent]. */
    fun hasLocalContent(): Boolean = initialSync.hasLocalContent()
}
