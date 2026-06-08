package tdz.sync.local

import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.js.json

/**
 * Production [LocalDir] over a File System Access `FileSystemDirectoryHandle` ([handle] is the dynamic
 * handle object). Mirrors the dynamic-interop style of DropboxStorage: handle methods return Promises
 * that we `await()`. `getDirectoryHandle`/`getFileHandle` reject with a NotFoundError when the child is
 * absent and `{create:false}` — caught and surfaced as null to satisfy the seam contract.
 */
class BrowserFsTree(private val handle: dynamic) : LocalDir {

    override suspend fun dir(name: String, create: Boolean): LocalDir? = try {
        val h = handle.getDirectoryHandle(name, json("create" to create))
            .unsafeCast<Promise<dynamic>>().await()
        BrowserFsTree(h)
    } catch (e: dynamic) {
        if (create) throw asException(e) else null
    }

    override suspend fun file(name: String, create: Boolean): LocalFile? = try {
        val h = handle.getFileHandle(name, json("create" to create))
            .unsafeCast<Promise<dynamic>>().await()
        BrowserFsFile(h)
    } catch (e: dynamic) {
        if (create) throw asException(e) else null
    }

    override suspend fun entries(): List<Entry> {
        val out = mutableListOf<Entry>()
        val iter = handle.values()   // async iterator of FileSystemHandle
        while (true) {
            val res = iter.next().unsafeCast<Promise<dynamic>>().await()
            if (res.done as Boolean) break
            val child = res.value
            val name = child.name as String
            when (child.kind as String) {
                "directory" -> out += DirEntry(name, BrowserFsTree(child))
                "file" -> out += FileEntry(name, BrowserFsFile(child))
            }
        }
        return out
    }

    override suspend fun remove(name: String) {
        try {
            handle.removeEntry(name).unsafeCast<Promise<dynamic>>().await()
        } catch (e: dynamic) {
            // NotFoundError: already gone — a missing entry is not an error per the seam contract.
        }
    }

    private fun asException(e: dynamic) = Exception((e?.message as? String) ?: e.toString())
}

/** Production [LocalFile] over a File System Access `FileSystemFileHandle`. */
class BrowserFsFile(private val handle: dynamic) : LocalFile {

    override suspend fun read(): String {
        val file = handle.getFile().unsafeCast<Promise<dynamic>>().await()
        return file.text().unsafeCast<Promise<String>>().await()
    }

    override suspend fun write(content: String) {
        val writable = handle.createWritable().unsafeCast<Promise<dynamic>>().await()
        writable.write(content).unsafeCast<Promise<dynamic>>().await()
        writable.close().unsafeCast<Promise<dynamic>>().await()
    }

    override suspend fun meta(): FileMeta {
        val file = handle.getFile().unsafeCast<Promise<dynamic>>().await()
        return FileMeta(lastModified = file.lastModified as Double, size = file.size as Double)
    }
}
