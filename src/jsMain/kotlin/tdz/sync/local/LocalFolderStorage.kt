package tdz.sync.local

import tdz.sync.RemoteFile
import tdz.sync.RemoteListing
import tdz.sync.RemoteStorage
import tdz.sync.RevConflictException
import tdz.sync.SyncPaths

/**
 * [RemoteStorage] whose "remote" is a local directory tree (the folder the user granted). Reuses the
 * exact path layout of [SyncPaths] — paths arrive `/`-prefixed (e.g. `/main/col-0.md`) and are mapped
 * onto nested [LocalDir]s. `rev` is `"<lastModified>-<size>"`: an opaque string (the engine only ever
 * compares revs for equality) that changes whenever the file changes; `serverModified` is the file's
 * last-modified time, so the engine's timestamp-based last-write-wins behaves identically to cloud.
 *
 * Only files matching our layout (`col-<n>.md`, `_canvas.json`) are listed, so a folder the user also
 * keeps other files in won't feed junk into the engine.
 */
class LocalFolderStorage(private val root: LocalDir) : RemoteStorage {

    override suspend fun list(folder: String): RemoteListing {
        val out = mutableListOf<RemoteFile>()
        walk(root, "", out)
        return RemoteListing(out, rootExists = true)
    }

    private suspend fun walk(dir: LocalDir, prefix: String, out: MutableList<RemoteFile>) {
        for (e in dir.entries()) {
            val path = "$prefix/${e.name}"
            when (e) {
                is DirEntry -> walk(e.dir, path, out)
                is FileEntry -> if (isSyncFile(e.name)) {
                    val m = e.file.meta()
                    out += RemoteFile(path, rev(m), m.lastModified)
                }
            }
        }
    }

    override suspend fun download(path: String): Pair<String, RemoteFile> {
        val file = navigateToFile(path, create = false) ?: throw Exception("local file not found: $path")
        val text = file.read()
        val m = file.meta()
        return text to RemoteFile(path, rev(m), m.lastModified)
    }

    override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
        val (parent, name) = navigateToParent(path, create = true)
            ?: throw Exception("bad local path: $path")
        val existing = parent.file(name, create = false)
        if (ifMatchRev != null && existing != null && rev(existing.meta()) != ifMatchRev) {
            throw RevConflictException(path)
        }
        val file = existing ?: parent.file(name, create = true)!!
        file.write(content)
        val m = file.meta()
        return RemoteFile(path, rev(m), m.lastModified)
    }

    override suspend fun delete(path: String) {
        val (parent, name) = navigateToParent(path, create = false) ?: return  // missing dir = nothing to do
        parent.remove(name)
    }

    override suspend fun revoke() { /* a local folder has no token to revoke */ }

    // --- helpers ---

    private fun rev(m: FileMeta) = "${m.lastModified.toLong()}-${m.size.toLong()}"

    private fun isSyncFile(name: String) = name == SyncPaths.MANIFEST || COLUMN_FILE.matches(name)

    private fun segments(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }

    /** Resolve to (parent directory, leaf name). Returns null if an intermediate dir is missing and not created. */
    private suspend fun navigateToParent(path: String, create: Boolean): Pair<LocalDir, String>? {
        val segs = segments(path)
        if (segs.isEmpty()) return null
        var dir: LocalDir = root
        for (i in 0 until segs.size - 1) {
            dir = dir.dir(segs[i], create) ?: return null
        }
        return dir to segs.last()
    }

    private suspend fun navigateToFile(path: String, create: Boolean): LocalFile? {
        val (parent, name) = navigateToParent(path, create) ?: return null
        return parent.file(name, create)
    }

    companion object {
        private val COLUMN_FILE = Regex("""^col-\d+\.md$""")
    }
}
