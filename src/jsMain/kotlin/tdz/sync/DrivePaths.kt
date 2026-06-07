package tdz.sync

/**
 * Pure path<->segment helpers for [DriveStorage]. Google Drive is ID-based with no real paths, so
 * the storage backend walks a folder chain by name; these functions turn the absolute [SyncPaths]
 * strings ("/base/Canvas/col-1.md") into the segment lists that walk needs, and reconstruct child
 * paths while descending during a list. Kept separate and pure so the tree-walk math is unit-tested
 * independently of the HTTP/GIS layer.
 */
object DrivePaths {

    /** Split an absolute path into its non-empty segments. "", "/" -> []. "/a/b.md" -> [a, b.md]. */
    fun segments(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    /** The folder segments (everything but the final filename). "/a/b/c.md" -> [a, b]. */
    fun folderSegments(path: String): List<String> {
        val s = segments(path)
        return if (s.isEmpty()) emptyList() else s.dropLast(1)
    }

    /** The final filename segment. "/a/b/c.md" -> "c.md"; "" -> "". */
    fun fileName(path: String): String = segments(path).lastOrNull() ?: ""

    /** Join a folder path and a child name into an absolute path ("/a" + "b.md" -> "/a/b.md"). */
    fun childPath(folderPath: String, name: String): String {
        val prefix = "/" + segments(folderPath).joinToString("/")
        return if (prefix == "/") "/$name" else "$prefix/$name"
    }
}
