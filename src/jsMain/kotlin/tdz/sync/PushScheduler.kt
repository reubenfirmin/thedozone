package tdz.sync

import kotlinx.browser.window as browserWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.js.Date
import tdz.controller.events.Event
import tdz.controller.events.SyncConflict
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.localstorage.SyncEntry
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus

/**
 * The push side of the sync vertical: debounce timers and the per-edit / per-structure push triggers
 * ([onColumnChanged], [onCanvasStructure]) plus the [pushesBlocked] gate. Records every local mutation
 * in the bookkeeping immediately (so nothing is lost) and schedules a debounced upload; the deletion
 * POLICY (orphan-as-deletion classification) lives in [SyncEngine.pushStructure].
 */
class PushScheduler(
    private val scope: CoroutineScope,
    private val eventBus: EventBus<Event>,
    private val adapter: ModelPathAdapter,
    private val facts: LocalFacts,
    private val engine: () -> SyncEngine?,
    private val readState: () -> SyncStateDoc,
    private val saveState: (SyncStateDoc) -> Unit,
    private val root: () -> String,
    private val debounceMs: () -> Int,
    private val onFailure: (Throwable) -> Unit,
) {
    private val pushTimers = mutableMapOf<String, Int>()   // path -> window timeout handle

    /**
     * True while local changes must be RECORDED but never pushed: auth/permission has lapsed
     * (NeedsReconnect / Error) or we're offline. Pushing anyway is worse than useless — with Drive
     * every push attempt mints a token, and a failed silent mint surfaces the Google auth popup,
     * one per edit. Blocked edits are marked pending (rev = null) with their version vector
     * bumped, so the reconcile that follows reconnect/online delivers them.
     */
    private fun pushesBlocked(): Boolean = when (SyncStateStore.current) {
        SyncState.NeedsReconnect, SyncState.Offline, is SyncState.Error -> true
        else -> false
    }

    fun onColumnChanged(canvasName: CanvasName, columnId: ColumnId) {
        val eng = engine() ?: return
        val path = SyncPaths.columnPath(root(), canvasName, columnId)
        val now = Date.now()
        val self = DeviceId.get()
        val prev = readState().entries[path]
        val stamped = (prev ?: SyncEntry(rev = null, localUpdatedAt = now))
            .copy(localUpdatedAt = now, vv = bump(prev?.vv ?: emptyMap(), self))
        if (pushesBlocked()) {
            // rev = null: without it a remote whose rev never moved reads as "up to date" on the
            // next pull, and the reconcile's pending sweep would skip this edit forever.
            saveState(readState().with(path, stamped.copy(rev = null)))
            return
        }
        saveState(readState().with(path, stamped))
        debouncePush(path) {
            // The column may have been deleted between the change and the debounce firing; the
            // structural path handles remote deletions.
            val content = adapter.contentFor(path, readState()) ?: return@debouncePush
            SyncStateStore.set(SyncState.Syncing, eventBus)
            // Fresh timestamp: the captured `now` is up to pushDebounceMs stale by the time this
            // runs, which would understate the edit's recency in last-write-wins comparison.
            val (newState, winner) = eng.push(path, content, Date.now(), readState())
            saveState(newState)
            // Re-push the manifest so the remote carries this column's updated version vector. If this
            // fails (the column already uploaded, so the remote vector would otherwise lag the column),
            // mark the manifest pending (rev = null) so the next reconcile retries it with the current vv.
            val manifestPath = SyncPaths.manifestPath(root(), canvasName)
            runCatching {
                adapter.manifestContent(canvasName, readState())?.let { mc ->
                    // Fresh timestamp: the captured `now` is up to pushDebounceMs+ stale by the time
                    // this runs, which would understate recency in any timestamp-based comparison.
                    saveState(eng.push(manifestPath, mc, Date.now(), readState()).first)
                }
            }.onFailure { t ->
                console.warn("[sync] manifest vv push failed; marking pending for retry: ${t.message}")
                val cur = readState().entries[manifestPath]
                saveState(readState().with(manifestPath,
                    (cur ?: SyncEntry(rev = null, localUpdatedAt = now)).copy(rev = null)))
            }
            winner?.let { eventBus.fire(SyncConflict(path, it)) }
            SyncStateStore.set(SyncState.Synced, eventBus)
        }
    }

    fun onCanvasStructure(canvasName: CanvasName) {
        engine() ?: return
        val manifestPath = SyncPaths.manifestPath(root(), canvasName)
        // Durability: mark the manifest pending NOW, synchronously. If the debounced push below
        // never runs (tab close, crash, offline), the next reconcile's pending sweep retries it —
        // a structural change can be delayed but never silently lost.
        val now = Date.now()
        val self = DeviceId.get()
        val prev = readState().entries[manifestPath]
        // Bump the STRUCTURE version vector: a layout/column-set change is a real edit, so it must be
        // causally newer than the remote — that's how "I cleared/changed my board while connected"
        // propagates and wins, instead of being treated like a never-edited board.
        saveState(readState().with(manifestPath,
            (prev ?: SyncEntry(rev = null, localUpdatedAt = now))
                .copy(rev = null, localUpdatedAt = now, vv = bump(prev?.vv ?: emptyMap(), self))))
        // Already marked pending above — the post-reconnect/online reconcile retries it.
        if (pushesBlocked()) return
        // Debounced per canvas: a burst of structural events (split + delete + import) coalesces
        // into one manifest push + orphan sweep, and this canvas's state writes serialize.
        debouncePush(manifestPath) {
            val eng = engine() ?: return@debouncePush
            if (!facts.canvasExists(canvasName)) return@debouncePush
            SyncStateStore.set(SyncState.Syncing, eventBus)
            // The engine owns the orphan-as-deletion policy (tombstone + manifest push + remote delete).
            val s = eng.pushStructure(root(), canvasName, manifestPath, readState(), facts) { st ->
                adapter.manifestContent(canvasName, st)
            }
            saveState(s)
            SyncStateStore.set(SyncState.Synced, eventBus)
        }
    }

    private fun debouncePush(path: String, block: suspend () -> Unit) {
        pushTimers[path]?.let { browserWindow.clearTimeout(it) }
        pushTimers[path] = browserWindow.setTimeout({
            pushTimers.remove(path)
            scope.launch {
                val t0 = Date.now()
                SyncLog.resetCounters()
                runCatching { block() }
                    .onSuccess { SyncLog.log("push '$path' done in ${SyncLog.since(t0)} — ${SyncLog.summary()}") }
                    .onFailure {
                        SyncLog.log("push '$path' FAILED after ${SyncLog.since(t0)} — ${SyncLog.summary()}: ${it.message}")
                        onFailure(it)
                    }
            }
        }, debounceMs())
    }

    /** Cancel any pending debounced pushes (on disconnect). */
    fun cancelAll() {
        pushTimers.values.forEach { browserWindow.clearTimeout(it) }
        pushTimers.clear()
    }
}
