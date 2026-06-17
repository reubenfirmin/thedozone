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

    fun canvasFolder(root: String, canvas: CanvasName): String {
        val prefix = rootPrefix(root)
        val name = canvas.normalized()
        // The ROOT canvas (home board) has an empty name: its files live at the prefix root, NOT in a
        // "" subfolder. Appending "/$name" here would yield "//col-N.md" — which Dropbox rejects as
        // path/malformed_path.
        return if (name.isEmpty()) prefix else "$prefix/$name"
    }

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
     * Inverse of [columnPath] without root context: returns (canvasName, columnId) or null if not a
     * column file. The canvas name is the LAST path segment only — suitable for callers that do not
     * have the root and do not need the full nested path (e.g. [manifestPathFor]). An empty segment
     * (a root-level `/col-N.md`) is the ROOT canvas, returned as `CanvasName("")`.
     * For a correct round-trip with nested boards use [parseColumn] with the root parameter.
     */
    fun parseColumn(path: String): Pair<CanvasName, ColumnId>? {
        val m = COLUMN_RE.find(path) ?: return null
        val col = ColumnId(m.groupValues[1].toInt())
        val beforeFile = path.substring(0, m.range.first)   // ".../<canvas>" or "" for a root column
        val canvas = beforeFile.substringAfterLast('/')     // "" = root canvas
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
        val beforeFile = path.substring(0, m.range.first)   // "<prefix>", "<prefix>/a/b/c", or ""
        val canvasPath = when {
            // Empty root: beforeFile is "/<canvas-path>" (or "" for the root canvas); strip leading "/".
            prefix.isEmpty() -> beforeFile.trimStart('/')
            // Non-empty root, root canvas: beforeFile is exactly the prefix (its files sit at the root).
            beforeFile == prefix -> ""
            // Non-empty root, named board: strip "<prefix>/".
            beforeFile.startsWith("$prefix/") -> beforeFile.removePrefix("$prefix/")
            else -> return null   // outside our root
        }
        return CanvasName(canvasPath) to col   // canvasPath "" = the root canvas
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
        val withoutManifest = path.removeSuffix("/$MANIFEST")   // "<prefix>", "<prefix>/a/b/c", or ""
        val canvasPath = when {
            prefix.isEmpty() -> withoutManifest.trimStart('/')
            withoutManifest == prefix -> ""                     // root canvas under a non-empty root
            withoutManifest.startsWith("$prefix/") -> withoutManifest.removePrefix("$prefix/")
            else -> return null
        }
        return CanvasName(canvasPath)   // "" = the root canvas
    }
}
