package tdz.sync.local

import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.js.json

/**
 * Persists the granted `FileSystemDirectoryHandle` in IndexedDB (handles are structured-cloneable but
 * not JSON-serializable, so localStorage can't hold them) and brokers File System Access permissions.
 *
 * Permission rules (Chromium): `requestPermission` requires a user gesture; `queryPermission` does not.
 * The picker and `requestPermission` Promises are therefore obtained synchronously by the caller inside
 * the click handler — see [pickDirectory] and [requestPermissionPromise].
 */
object HandleStore {

    private const val STORE = "handles"
    private const val KEY = "rootDir"

    /** True on browsers that expose the local-disk directory picker (Chromium). */
    fun supported(): Boolean =
        js("typeof window !== 'undefined' && 'showDirectoryPicker' in window") as Boolean

    /** Open the directory picker in read-write mode. MUST be called synchronously from a user gesture. */
    fun pickDirectory(): Promise<dynamic> =
        js("window.showDirectoryPicker({ mode: 'readwrite' })").unsafeCast<Promise<dynamic>>()

    suspend fun queryPermission(handle: dynamic): String =
        handle.queryPermission(json("mode" to "readwrite")).unsafeCast<Promise<String>>().await()

    /** MUST be called synchronously from a user gesture; the caller awaits the returned Promise. */
    fun requestPermissionPromise(handle: dynamic): Promise<String> =
        handle.requestPermission(json("mode" to "readwrite")).unsafeCast<Promise<String>>()

    suspend fun save(handle: dynamic) {
        val db = openDb().await()
        runStore(db, "readwrite") { store -> store.put(handle, KEY) }.await()
    }

    /** The stored handle, or null if none has been saved. */
    suspend fun load(): dynamic {
        val db = openDb().await()
        val result = runStore(db, "readonly") { store -> store.get(KEY) }.await()
        return if (result == undefined) null else result
    }

    suspend fun clear() {
        val db = openDb().await()
        runStore(db, "readwrite") { store -> store.delete(KEY) }.await()
    }

    private fun openDb(): Promise<dynamic> = Promise { resolve, reject ->
        val req = js("indexedDB.open('tdz-fs', 1)")
        req.onupgradeneeded = { _: dynamic -> req.result.createObjectStore(STORE); Unit }
        req.onsuccess = { _: dynamic -> resolve(req.result); Unit }
        req.onerror = { _: dynamic -> reject(Exception("indexedDB open failed")); Unit }
    }

    private fun runStore(db: dynamic, mode: String, op: (store: dynamic) -> dynamic): Promise<dynamic> =
        Promise { resolve, reject ->
            val tx = db.transaction(STORE, mode)
            val store = tx.objectStore(STORE)
            val req = op(store)
            req.onsuccess = { _: dynamic -> resolve(req.result); Unit }
            req.onerror = { _: dynamic -> reject(Exception("indexedDB op failed")); Unit }
        }
}
