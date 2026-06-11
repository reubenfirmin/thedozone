package tdz.sync.local

import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.js.json

/**
 * Persists the granted [FileSystemDirectoryHandle] in IndexedDB (handles are structured-cloneable but
 * not JSON-serializable, so localStorage can't hold them) and brokers File System Access permissions.
 *
 * Permission rules (Chromium): `requestPermission` requires a user gesture; `queryPermission` does not.
 * The picker and `requestPermission` Promises are therefore obtained synchronously by the caller inside
 * the click handler — see [pickDirectory] and [requestPermissionPromise].
 */
object HandleStore {

    private const val STORE = "handles"
    private const val KEY = "rootDir"

    private val readwrite = json("mode" to "readwrite")

    /** True on browsers that expose the local-disk directory picker (Chromium). */
    fun supported(): Boolean =
        js("typeof window !== 'undefined' && 'showDirectoryPicker' in window") as Boolean

    /** Open the directory picker in read-write mode. MUST be called synchronously from a user gesture. */
    fun pickDirectory(): Promise<FileSystemDirectoryHandle> = showDirectoryPicker(readwrite)

    suspend fun queryPermission(handle: FileSystemDirectoryHandle): String =
        handle.queryPermission(readwrite).await()

    /** MUST be called synchronously from a user gesture; the caller awaits the returned Promise. */
    fun requestPermissionPromise(handle: FileSystemDirectoryHandle): Promise<String> =
        handle.requestPermission(readwrite)

    suspend fun save(handle: FileSystemDirectoryHandle) {
        val db = openDb().await()
        runStore(db, "readwrite") { store -> store.put(handle, KEY) }.await()
    }

    /** The stored handle, or null if none has been saved. */
    suspend fun load(): FileSystemDirectoryHandle? {
        val db = openDb().await()
        val result = runStore(db, "readonly") { store -> store.get(KEY) }.await()
        // IDB values are untyped by nature; this is the one narrowing at the storage boundary
        // (external interfaces cannot be `as?`-checked).
        return if (result == null || result == undefined) null
            else result.unsafeCast<FileSystemDirectoryHandle>()
    }

    suspend fun clear() {
        val db = openDb().await()
        runStore(db, "readwrite") { store -> store.delete(KEY) }.await()
    }

    private fun openDb(): Promise<IdbDatabase> = Promise { resolve, reject ->
        val req = indexedDB.open("tdz-fs", 1)
        req.onupgradeneeded = { req.result.createObjectStore(STORE) }
        req.onsuccess = { resolve(req.result) }
        req.onerror = { reject(Exception("indexedDB open failed")) }
    }

    private fun runStore(db: IdbDatabase, mode: String, op: (store: IdbObjectStore) -> IdbRequest<Any?>): Promise<Any?> =
        Promise { resolve, reject ->
            val req = op(db.transaction(STORE, mode).objectStore(STORE))
            req.onsuccess = { resolve(req.result) }
            req.onerror = { reject(Exception("indexedDB op failed")) }
        }
}
