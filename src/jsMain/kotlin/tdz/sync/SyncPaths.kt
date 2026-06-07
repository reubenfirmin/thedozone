package tdz.sync

import tdz.model.CanvasName
import tdz.model.ColumnId

/**
 * Pure mapping between (canvas, column) and remote paths, shared by every provider. Layout:
 *   <root>/<canvas>/col-<id>.md   per column
 *   <root>/<canvas>/_canvas.json  per-canvas manifest (CanvasDoc JSON)
 *
 * Case is PRESERVED via CanvasName.normalized() (which substitutes filesystem-unsafe chars and is
 * idempotent, but does NOT lowercase). This keeps the path<->model round-trip exact: push uses
 * `canvas.normalized()`, and parseColumn yields a CanvasName whose normalized() equals that same
 * folder segment, so it resolves to the identical model key. Storage backends must surface the
 * original-case path (Dropbox `path_display`, Drive file/folder names) so list/download preserve it.
 * Dropbox folder lookup is case-insensitive, so two canvases differing only in case would collide
 * remotely — an accepted edge case.
 */
object SyncPaths {

    const val MANIFEST = "_canvas.json"
    private val COLUMN_RE = Regex("""/col-(\d+)\.md$""")

    /**
     * Normalize a user-entered root folder into a path prefix: `"/a/b/"`, `"a/b"`, `"/"`, `""`
     * → `"/a/b"` or `""` (empty = the provider's root). Use this anywhere a raw config value reaches
     * a storage backend — Dropbox `list_folder` rejects a non-empty path that doesn't start with `/`.
     */
    fun rootPrefix(root: String): String {
        val trimmed = root.trim().trim('/')
        return if (trimmed.isEmpty()) "" else "/$trimmed"
    }

    fun canvasFolder(root: String, canvas: CanvasName): String =
        "${rootPrefix(root)}/${canvas.normalized()}"

    fun columnPath(root: String, canvas: CanvasName, col: ColumnId): String =
        "${canvasFolder(root, canvas)}/col-$col.md"

    fun manifestPath(root: String, canvas: CanvasName): String =
        "${canvasFolder(root, canvas)}/$MANIFEST"

    fun isManifest(path: String): Boolean = path.endsWith("/$MANIFEST")

    /** Inverse of [columnPath]: returns (canvasName, columnId) or null if not a column file. */
    fun parseColumn(path: String): Pair<CanvasName, ColumnId>? {
        val m = COLUMN_RE.find(path) ?: return null
        val col = m.groupValues[1].toInt()
        val beforeFile = path.substring(0, m.range.first)   // ".../<canvas>"
        val canvas = beforeFile.substringAfterLast('/')
        if (canvas.isEmpty()) return null
        return CanvasName(canvas) to col
    }
}
