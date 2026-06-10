package tdz.sync.local

import kotlin.js.Json
import kotlin.js.Promise

/**
 * Typed external declarations for the File System Access API (Chromium-only). kotlin-wrappers'
 * `web.fs` covers the standard surface but NOT the Chromium-only permission API
 * (`queryPermission`/`requestPermission`) this feature depends on, so the handful of members the
 * app touches are declared here, Promise-returning, and awaited by the callers. Options objects
 * are passed as [Json] (`json("mode" to "readwrite")`) — typed enough for a two-key literal
 * without inventing builder types.
 *
 * https://developer.mozilla.org/en-US/docs/Web/API/File_System_API
 */
external interface FileSystemHandle {
    /** "file" | "directory" */
    val kind: String
    val name: String
    fun queryPermission(options: Json): Promise<String>
    fun requestPermission(options: Json): Promise<String>
}

external interface FileSystemDirectoryHandle : FileSystemHandle {
    fun getDirectoryHandle(name: String, options: Json): Promise<FileSystemDirectoryHandle>
    fun getFileHandle(name: String, options: Json): Promise<FileSystemFileHandle>
    fun removeEntry(name: String): Promise<Unit>
    /** Async iterator over the directory's child handles. */
    fun values(): FileSystemHandleIterator
}

external interface FileSystemHandleIterator {
    fun next(): Promise<FileSystemHandleIterationResult>
}

external interface FileSystemHandleIterationResult {
    val done: Boolean
    val value: FileSystemHandle?
}

external interface FileSystemFileHandle : FileSystemHandle {
    fun getFile(): Promise<FsFile>
    fun createWritable(): Promise<FileSystemWritableFileStream>
}

/** The subset of `File` this app reads (org.w3c.files.File predates `Blob.text()`). */
external interface FsFile {
    val lastModified: Double
    val size: Double
    fun text(): Promise<String>
}

external interface FileSystemWritableFileStream {
    fun write(data: String): Promise<Unit>
    fun close(): Promise<Unit>
}

/** `window.showDirectoryPicker` — global on Chromium; gate calls behind [HandleStore.supported]. */
external fun showDirectoryPicker(options: Json): Promise<FileSystemDirectoryHandle>

// ---- The minimal IndexedDB surface used to persist the directory handle ----

external interface IdbRequest<out T> {
    val result: T
    var onsuccess: ((Any?) -> Unit)?
    var onerror: ((Any?) -> Unit)?
}

external interface IdbOpenRequest : IdbRequest<IdbDatabase> {
    var onupgradeneeded: ((Any?) -> Unit)?
}

external interface IdbDatabase {
    fun createObjectStore(name: String): IdbObjectStore
    fun transaction(name: String, mode: String): IdbTransaction
}

external interface IdbTransaction {
    fun objectStore(name: String): IdbObjectStore
}

external interface IdbObjectStore {
    fun put(value: Any?, key: String): IdbRequest<Any?>
    fun get(key: String): IdbRequest<Any?>
    fun delete(key: String): IdbRequest<Any?>
}

external interface IdbFactory {
    fun open(name: String, version: Int): IdbOpenRequest
}

external val indexedDB: IdbFactory
