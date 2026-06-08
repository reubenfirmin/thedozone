package tdz.sync.local

/** Size in bytes and last-modified epoch millis for a stored file. */
data class FileMeta(val lastModified: Double, val size: Double)

/** A single file in the local tree. Backed in production by a FileSystemFileHandle; faked in tests. */
interface LocalFile {
    suspend fun read(): String
    suspend fun write(content: String)
    suspend fun meta(): FileMeta
}

/** A directory entry yielded by [LocalDir.entries]. */
sealed interface Entry { val name: String }
data class DirEntry(override val name: String, val dir: LocalDir) : Entry
data class FileEntry(override val name: String, val file: LocalFile) : Entry

/**
 * A directory in the local tree. [dir]/[file] return null when the child is absent and [create] is
 * false; they create (and return) the child when [create] is true. Backed in production by a
 * FileSystemDirectoryHandle; faked in tests.
 */
interface LocalDir {
    suspend fun dir(name: String, create: Boolean): LocalDir?
    suspend fun file(name: String, create: Boolean): LocalFile?
    suspend fun entries(): List<Entry>
    suspend fun remove(name: String)
}
