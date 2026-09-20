package tdz.model.localstorage

import tdz.model.*
import kotlinx.browser.window

/**
 * Write-behind buffer, shared across [LocalStorageModel] instances (localStorage is one global, so
 * its buffer must be too). store() records here and the synchronous localStorage.setItem runs
 * AFTER the current task — never between a click and its render. retrieve() consults the buffer
 * first, so every reader (including the Model parse cache, which keys on the raw string) sees the
 * latest write immediately, flushed or not. Durability: flushed one task later, and forced on
 * pagehide / tab backgrounding — the at-risk window is milliseconds, not "until the user leaves".
 */
private object WriteBehind {
    val pending = LinkedHashMap<String, String>()
    private var scheduled = false
    private var hooksInstalled = false

    fun write(key: String, value: String) {
        pending[key] = value   // value is the encoded snapshot — mutation-safe to defer
        if (!hooksInstalled) {
            hooksInstalled = true
            window.addEventListener("pagehide", { flush() })
            window.addEventListener("visibilitychange", {
                if (kotlinx.browser.document.asDynamic().visibilityState == "hidden") flush()
            })
        }
        if (!scheduled) {
            scheduled = true
            window.setTimeout({ flush() }, 0)
        }
    }

    fun flush() {
        scheduled = false
        if (pending.isEmpty()) return
        pending.forEach { (k, v) -> window.localStorage.setItem(k, v) }
        pending.clear()
    }
}

/**
 * Force any write-behind buffered writes into localStorage NOW. Raw localStorage readers/writers
 * (the backup capture/restore in [tdz.sync.LocalBackup]) must call this first: without it a
 * capture can miss an edit still sitting in the buffer, and a buffered write flushing AFTER a
 * restore would clobber the restored value.
 */
fun flushPendingWrites() = WriteBehind.flush()

class LocalStorageModel {

    private fun storageKey(namespace: Namespace, key: String): String {
        return namespace.toString() + key
    }

    private fun store(namespace: Namespace, item: HasKey) {
        WriteBehind.write(storageKey(namespace, item.key), item.encode())
    }

    private fun retrieve(namespace: Namespace, key: String): String? {
        val k = storageKey(namespace, key)
        return WriteBehind.pending[k] ?: window.localStorage.getItem(k)
    }

    private fun remove(namespace: Namespace, key: String) {
        val k = storageKey(namespace, key)
        WriteBehind.pending.remove(k)   // a delete also cancels any not-yet-flushed write
        window.localStorage.removeItem(k)
    }

    fun getCanvas(canvasName: CanvasName): Canvas? =
        retrieve(Namespace.NEWCANVAS, canvasName.normalized())?.let {
            val doc = CanvasDoc.decode(it)
            Canvas(doc.name, doc.parentName, doc.columns, doc.layouts)
        }

    fun storeCanvas(canvas: Canvas) {
        store(Namespace.NEWCANVAS, CanvasDoc(canvas.name, canvas.parent, canvas.columns, canvas.layouts))
    }

    fun getColumn(canvasName: CanvasName, columnId: ColumnId): String? {
        return retrieve(Namespace.COLUMN, ColumnDoc.toKey(canvasName, ColumnDoc.toLabel(columnId)))?.let {
            ColumnDoc.decode(it).value
        }
    }

    fun deleteCanvas(canvasName: CanvasName) {
        remove(Namespace.NEWCANVAS, canvasName.normalized())
    }

    /** Every canvas that has a manifest in storage (the source for an "all boards" list). */
    fun allCanvasNames(): List<CanvasName> {
        flushPendingWrites()
        val prefix = Namespace.NEWCANVAS.toString()
        val ls = window.localStorage
        val out = mutableListOf<CanvasName>()
        for (i in 0 until ls.length) {
            val k = ls.key(i) ?: continue
            if (k.startsWith(prefix)) out.add(CanvasName(k.removePrefix(prefix)))
        }
        return out
    }

    fun deleteColumn(canvasName: CanvasName, column: ColumnId) {
        remove(Namespace.COLUMN, ColumnDoc.toKey(canvasName, ColumnDoc.toLabel(column)))
        // Don't orphan the column's mode flag — deleting content used to leave COLUMN_MODE behind.
        window.localStorage.removeItem(CanvasStorageKeys.columnMode(canvasName, column))
    }

    /**
     * Remove OUR orphaned keys, SCOPED strictly to our namespaces (never another app's keys on this
     * shared origin): COLUMN_MODE flags whose (canvas,col) no longer exists in any manifest, and the
     * legacy `DROPBOX_CONFIGconfig` superseded by SYNC_CONFIG. Mode flags carry no data and aren't
     * synced, so removing orphaned ones is safe. Orphaned column *content* is NOT deleted here — it
     * may be real data and a local delete can resurrect via a sync pull; it's returned for reporting
     * so the user can recover (Restore) or purge it deliberately. Returns (modesPurged, orphanContentKeys).
     */
    fun purgeOrphans(): Pair<Int, List<String>> {
        flushPendingWrites()
        val ls = window.localStorage
        val validModes = mutableSetOf<String>()
        val validContent = mutableSetOf<String>()
        for (i in 0 until ls.length) {
            val k = ls.key(i) ?: continue
            if (!k.startsWith(Namespace.NEWCANVAS.toString())) continue
            val doc = runCatching { CanvasDoc.decode(ls.getItem(k) ?: "") }.getOrNull() ?: continue
            for (col in doc.columns) {
                validModes += CanvasStorageKeys.columnMode(doc.name, col)
                validContent += CanvasStorageKeys.column(doc.name, col)
            }
        }
        val modePrefix = Namespace.COLUMN_MODE.toString()
        val colPrefix = Namespace.COLUMN.toString()
        val purge = mutableListOf<String>()
        val orphanContent = mutableListOf<String>()
        for (i in 0 until ls.length) {
            val k = ls.key(i) ?: continue
            when {
                k == "DROPBOX_CONFIGconfig" -> purge += k                                   // legacy ours
                k.startsWith(modePrefix) && k !in validModes -> purge += k                  // orphan mode (no data)
                k.startsWith(colPrefix) && !k.startsWith(modePrefix) && k !in validContent -> orphanContent += k
            }
        }
        purge.forEach { ls.removeItem(it) }
        return purge.size to orphanContent
    }

    fun storeColumn(column: ColumnId, canvasName: CanvasName, value: String) {
        store(Namespace.COLUMN, ColumnDoc(ColumnDoc.toLabel(column), canvasName, value))
    }

    /** A corrupt config falls back to [SyncConfigDoc.DISABLED] (sync off) rather than crashing the app. */
    fun readSyncConfig(): SyncConfigDoc =
        retrieve(Namespace.SYNC_CONFIG, SyncConfigDoc.singletonKey())
            ?.let {
                runCatching { SyncConfigDoc.decode(it) }
                    .onFailure { e -> console.error("Corrupt sync config ignored: ${e.message}") }
                    .getOrNull()
            }
            ?: SyncConfigDoc.DISABLED

    fun writeSyncConfig(doc: SyncConfigDoc) = store(Namespace.SYNC_CONFIG, doc)

    /** Corrupt sync state falls back to empty bookkeeping (a full re-reconcile, never a crash). */
    fun readSyncState(): SyncStateDoc =
        retrieve(Namespace.SYNC_STATE, SyncStateDoc.singletonKey())
            ?.let {
                runCatching { SyncStateDoc.decode(it) }
                    .onFailure { e -> console.error("Corrupt sync state ignored: ${e.message}") }
                    .getOrNull()
            }
            ?: SyncStateDoc.EMPTY

    fun writeSyncState(doc: SyncStateDoc) = store(Namespace.SYNC_STATE, doc)

    /** The stored column mode, or null when unset (or an unknown value from a future build). */
    fun getColumnMode(canvasName: CanvasName, columnId: ColumnId): Mode? {
        val raw = window.localStorage.getItem(CanvasStorageKeys.columnMode(canvasName, columnId)) ?: return null
        return Mode.entries.firstOrNull { it.name == raw }
    }

    fun storeColumnMode(canvasName: CanvasName, columnId: ColumnId, mode: Mode) {
        window.localStorage.setItem(CanvasStorageKeys.columnMode(canvasName, columnId), mode.name)
    }

    fun getTheme(): ThemeName? =
        window.localStorage.getItem(storageKey(Namespace.THEME, THEME_KEY))?.let { ThemeName(it) }

    fun storeTheme(name: ThemeName) =
        window.localStorage.setItem(storageKey(Namespace.THEME, THEME_KEY), name.value)

    private companion object {
        /** The (single, global) theme is stored under this key within [Namespace.THEME]. */
        const val THEME_KEY = "current"
    }
}
