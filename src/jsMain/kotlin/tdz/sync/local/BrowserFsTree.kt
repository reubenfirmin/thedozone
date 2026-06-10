package tdz.sync.local

import kotlinx.coroutines.await
import kotlin.js.json

/**
 * Production [LocalDir] over a File System Access [FileSystemDirectoryHandle] (typed externals in
 * FileSystemAccess.kt). `getDirectoryHandle`/`getFileHandle` reject with a NotFoundError when the
 * child is absent and `{create:false}` — caught and surfaced as null to satisfy the seam contract.
 */
class BrowserFsTree(private val handle: FileSystemDirectoryHandle) : LocalDir {

    override suspend fun dir(name: String, create: Boolean): LocalDir? = try {
        BrowserFsTree(handle.getDirectoryHandle(name, json("create" to create)).await())
    } catch (e: Throwable) {
        if (create) throw e else null
    }

    override suspend fun file(name: String, create: Boolean): LocalFile? = try {
        BrowserFsFile(handle.getFileHandle(name, json("create" to create)).await())
    } catch (e: Throwable) {
        if (create) throw e else null
    }

    override suspend fun entries(): List<Entry> {
        val out = mutableListOf<Entry>()
        val iter = handle.values()
        while (true) {
            val res = iter.next().await()
            if (res.done) break
            val child = res.value ?: continue
            when (child.kind) {
                // The kind discriminates the handle subtype; external interfaces can't be
                // `as?`-checked, so narrow with a cast guarded by the discriminator.
                "directory" -> out += DirEntry(child.name, BrowserFsTree(child.unsafeCast<FileSystemDirectoryHandle>()))
                "file" -> out += FileEntry(child.name, BrowserFsFile(child.unsafeCast<FileSystemFileHandle>()))
            }
        }
        return out
    }

    override suspend fun remove(name: String) {
        try {
            handle.removeEntry(name).await()
        } catch (e: Throwable) {
            // NotFoundError: already gone — a missing entry is not an error per the seam contract.
        }
    }
}

/** Production [LocalFile] over a File System Access [FileSystemFileHandle]. */
class BrowserFsFile(private val handle: FileSystemFileHandle) : LocalFile {

    override suspend fun read(): String =
        handle.getFile().await().text().await()

    override suspend fun write(content: String) {
        val writable = handle.createWritable().await()
        writable.write(content).await()
        writable.close().await()
    }

    override suspend fun meta(): FileMeta {
        val file = handle.getFile().await()
        return FileMeta(lastModified = file.lastModified, size = file.size)
    }
}
