package tdz.model.localstorage

import tdz.model.*
import kotlinx.browser.window

class LocalStorageModel {

    private fun storageKey(namespace: Namespace, key: String): String {
        return namespace.toString() + key
    }

    private fun store(namespace: Namespace, item: HasKey) {
        window.localStorage.setItem(storageKey(namespace, item.key), item.encode())
    }

    private fun retrieve(namespace: Namespace, key: String): String? =
        window.localStorage.getItem(storageKey(namespace, key))

    private fun remove(namespace: Namespace, key: String) {
        window.localStorage.removeItem(storageKey(namespace, key))
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

    fun deleteColumn(canvasName: CanvasName, column: ColumnId) {
        remove(Namespace.COLUMN, ColumnDoc.toKey(canvasName, ColumnDoc.toLabel(column)))
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
        val key = "${canvasName.normalized()}-$columnId"
        val raw = window.localStorage.getItem(storageKey(Namespace.COLUMN_MODE, key)) ?: return null
        return Mode.entries.firstOrNull { it.name == raw }
    }

    fun storeColumnMode(canvasName: CanvasName, columnId: ColumnId, mode: Mode) {
        val key = "${canvasName.normalized()}-$columnId"
        window.localStorage.setItem(storageKey(Namespace.COLUMN_MODE, key), mode.name)
    }

    fun getTheme(): String? = window.localStorage.getItem(storageKey(Namespace.THEME, "current"))

    fun storeTheme(name: String) = window.localStorage.setItem(storageKey(Namespace.THEME, "current"), name)
}
