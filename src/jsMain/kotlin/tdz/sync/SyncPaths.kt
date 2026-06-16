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

    /** The manifest path for the canvas that owns [columnPath], or null if it isn't a column file. */
    fun manifestPathFor(columnPath: String): String? {
        if (parseColumn(columnPath) == null) return null
        val canvasFolder = columnPath.substringBeforeLast('/')
        return "$canvasFolder/$MANIFEST"
    }

    /**
     * Inverse of [columnPath] without root context: returns (canvasName, columnId) or null.
     * The canvas name is the LAST path segment only — suitable for callers that do not have the
     * root and do not need the full nested path (e.g. [manifestPathFor]).
     * For a correct round-trip with nested boards use [parseColumn] with the root parameter.
     */
    fun parseColumn(path: String): Pair<CanvasName, ColumnId>? {
        val m = COLUMN_RE.find(path) ?: return null
        val col = ColumnId(m.groupValues[1].toInt())
        val beforeFile = path.substring(0, m.range.first)   // ".../<canvas>"
        val canvas = beforeFile.substringAfterLast('/')
        if (canvas.isEmpty()) return null
        return CanvasName(canvas) to col
    }

    /**
     * Root-aware inverse of [columnPath]: strips [rootPrefix] from the front and the trailing
     * `/col-<id>.md` from the end; everything in between is the FULL canvas path (may contain
     * `/` for nested boards). Returns `(CanvasName(fullPath), ColumnId)` or null if not a column
     * file or the path does not start with the expected root prefix.
     *
     * Round-trip invariant: `parseColumn(columnPath(root, canvas, col), root) == (canvas, col)`
     * (comparing via `CanvasName.normalized()`).
     */
    fun parseColumn(path: String, root: String): Pair<CanvasName, ColumnId>? {
        val m = COLUMN_RE.find(path) ?: return null
        val col = ColumnId(m.groupValues[1].toInt())
        val prefix = rootPrefix(root)
        val beforeFile = path.substring(0, m.range.first)   // "<prefix>/a/b/c"
        // Strip leading root prefix (e.g. "/notes" or "")
        val canvasPath = if (prefix.isEmpty()) {
            // Empty root: beforeFile is "/<canvas-path>"; strip the leading "/"
            beforeFile.trimStart('/')
        } else {
            // Non-empty root: beforeFile starts with "<prefix>/"; strip prefix + separator
            if (!beforeFile.startsWith("$prefix/")) return null
            beforeFile.removePrefix("$prefix/")
        }
        if (canvasPath.isEmpty()) return null
        return CanvasName(canvasPath) to col
    }

    /**
     * Root-aware inverse of [manifestPath]: strips [rootPrefix] from the front and the trailing
     * `/_canvas.json` from the end; everything in between is the FULL canvas path. Returns
     * `CanvasName(fullPath)` or null if not a manifest path or prefix mismatch.
     *
     * Round-trip invariant: `parseManifest(manifestPath(root, canvas), root)?.normalized() == canvas.normalized()`
     */
    fun parseManifest(path: String, root: String): CanvasName? {
        if (!path.endsWith("/$MANIFEST")) return null
        val prefix = rootPrefix(root)
        val withoutManifest = path.removeSuffix("/$MANIFEST")   // "<prefix>/a/b/c"
        val canvasPath = if (prefix.isEmpty()) {
            withoutManifest.trimStart('/')
        } else {
            if (!withoutManifest.startsWith("$prefix/")) return null
            withoutManifest.removePrefix("$prefix/")
        }
        if (canvasPath.isEmpty()) return null
        return CanvasName(canvasPath)
    }
}
