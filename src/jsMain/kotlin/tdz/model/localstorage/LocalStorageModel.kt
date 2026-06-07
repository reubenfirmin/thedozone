package tdz.model.localstorage

import tdz.model.*
import kotlinx.browser.window
import kotlin.js.Json

class LocalStorageModel {

    private fun storageKey(namespace: Namespace, key: String): String {
        return namespace.toString() + key
    }

    private fun <T: HasKey> store(namespace: Namespace, item: T) {
        window.localStorage.setItem(storageKey(namespace, item.key), JSON.stringify(item.toJson()))
    }

    private fun retrieve(namespace: Namespace, key: String): Json? {
        return window.localStorage.getItem(storageKey(namespace, key))?.let {
            return JSON.parse<Json>(it)
        }
    }

    private fun remove(namespace: Namespace, key: String) {
        window.localStorage.removeItem(storageKey(namespace, key))
    }

    fun getCanvas(canvasName: CanvasName): Canvas? {
        val Canvas = retrieve(Namespace.NEWCANVAS, canvasName.normalized())

        return if (Canvas != null) {
            val CanvasDoc = CanvasDoc.fromJson(Canvas)
            Canvas(CanvasDoc.name, CanvasDoc.parentName, CanvasDoc.columns, CanvasDoc.layouts)
        } else {
            null
        }
    }

    fun storeCanvas(canvas: Canvas) {
        store(Namespace.NEWCANVAS, CanvasDoc(canvas.name, canvas.parent, canvas.columns, canvas.layouts))
    }

    fun getColumn(canvasName: CanvasName, columnId: ColumnId): String? {
        return retrieve(Namespace.COLUMN, ColumnDoc.toKey(canvasName, ColumnDoc.toLabel(columnId))) ?.let {
            ColumnDoc.fromJson(it).value
        }
    }

    fun readVersion(): VersionDoc? {
        return retrieve(Namespace.VERSION, VersionDoc.singletonKey())?.let {
            VersionDoc.fromJson(it)
        }
    }

    fun writeVersion(doc: VersionDoc) {
        store(Namespace.VERSION, doc)
    }

    fun deleteCanvas(canvasName: CanvasName) {
        remove(Namespace.NEWCANVAS, canvasName.normalized())
    }

    fun deleteColumn(canvasName: CanvasName, column: Int) {
        remove(Namespace.COLUMN, ColumnDoc.toKey(canvasName, ColumnDoc.toLabel(column)))
    }

    fun storeColumn(column: ColumnId, canvasName: CanvasName, value: String) {
        store(Namespace.COLUMN, ColumnDoc(ColumnDoc.toLabel(column), canvasName, value))
    }

    fun readSyncConfig(): SyncConfigDoc =
        retrieve(Namespace.DROPBOX_CONFIG, SyncConfigDoc.singletonKey())
            ?.let { SyncConfigDoc.fromJson(it) }
            ?: SyncConfigDoc.DISABLED

    fun writeSyncConfig(doc: SyncConfigDoc) = store(Namespace.DROPBOX_CONFIG, doc)

    fun readSyncState(): SyncStateDoc =
        retrieve(Namespace.SYNC_STATE, SyncStateDoc.singletonKey())
            ?.let { SyncStateDoc.fromJson(it) }
            ?: SyncStateDoc.EMPTY

    fun writeSyncState(doc: SyncStateDoc) = store(Namespace.SYNC_STATE, doc)

    fun getColumnMode(canvasName: CanvasName, columnId: ColumnId): String? {
        val key = "${canvasName.normalized()}-$columnId"
        return window.localStorage.getItem(storageKey(Namespace.COLUMN_MODE, key))
    }

    fun storeColumnMode(canvasName: CanvasName, columnId: ColumnId, mode: String) {
        val key = "${canvasName.normalized()}-$columnId"
        window.localStorage.setItem(storageKey(Namespace.COLUMN_MODE, key), mode)
    }

    fun getTheme(): String? = window.localStorage.getItem(storageKey(Namespace.THEME, "current"))

    fun storeTheme(name: String) = window.localStorage.setItem(storageKey(Namespace.THEME, "current"), name)
}