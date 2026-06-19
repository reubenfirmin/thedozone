package tdz.sync

import tdz.controller.events.ColumnsChanged
import tdz.controller.events.Event
import tdz.controller.events.RefreshCanvas
import tdz.controller.events.RefreshColumns
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.Model
import tdz.model.localstorage.CanvasDoc
import tdz.model.localstorage.SyncStateDoc
import zoned.framework.events.EventBus

/**
 * The path <-> [Model] translation handed to [SyncEngine] as its read/write/delete callbacks, plus the
 * single producer of push content. The engine speaks remote paths; this is the only place that knows
 * how a path maps to a model read/write and how a manifest's bytes are assembled.
 *
 * [contentFor] is THE one way to serialize a file for a push: columns yield their raw local bytes,
 * manifests yield the manifest serialized WITH its version-vector section (from a supplied
 * [SyncStateDoc]). This removes the old "remember to call manifestContent instead of CanvasDoc.encode()"
 * convention — a caller can no longer accidentally drop the vectors, because there is no second way to
 * produce manifest bytes. [readLocal] (used by the engine for existence checks and structure
 * comparisons) reuses the same producer, reading the persisted state via [readState].
 */
class ModelPathAdapter(
    private val model: Model,
    private val eventBus: EventBus<Event>,
    private val root: () -> String,
    private val readState: () -> SyncStateDoc,
) {

    /** Read the file at [path]: column -> raw bytes; manifest -> vv-complete bytes from persisted state. */
    fun readLocal(path: String): String? {
        SyncPaths.parseColumn(path, root())?.let { (canvas, col) ->
            return model.getCanvasColumnRaw(canvas, col)
        }
        if (SyncPaths.isManifest(path)) {
            val canvas = manifestCanvas(path) ?: return null
            return manifestContent(canvas, readState())
        }
        return null
    }

    /** Apply a pulled remote file locally; fire remote-origin refresh events (never re-pushed). */
    fun writeLocal(path: String, content: String) {
        SyncPaths.parseColumn(path, root())?.let { (canvas, col) ->
            model.updateColumn(canvas, col, content)
            // remote=true: this change came FROM the remote; subscribers must not push it back.
            eventBus.fire(ColumnsChanged(canvas, setOf(col), remote = true))
            eventBus.fire(RefreshColumns(canvas, setOf(col)))
            return
        }
        if (SyncPaths.isManifest(path)) {
            val doc = CanvasDoc.decode(content)
            model.addCanvas(Canvas(doc.name, doc.parentName, doc.columns, doc.layouts))
            // remote=true: this manifest came FROM the remote — re-render, but never push it back.
            eventBus.fire(RefreshCanvas(doc.name, remote = true))
        }
    }

    fun deleteLocal(path: String) {
        SyncPaths.parseColumn(path, root())?.let { (canvas, col) ->
            model.deleteColumn(canvas, col)
            eventBus.fire(RefreshColumns(canvas, setOf(col)))
        }
    }

    fun manifestCanvas(path: String): CanvasName? = SyncPaths.parseManifest(path, root())

    /**
     * The single producer of push content for [path] against [state]:
     * - a column path yields its raw local bytes (null if it no longer exists),
     * - a manifest path yields [manifestContent] for the parsed canvas (the manifest serialized WITH
     *   its version vectors), so callers never have to special-case manifests or risk dropping `vv`.
     */
    fun contentFor(path: String, state: SyncStateDoc): String? {
        SyncPaths.parseColumn(path, root())?.let { (canvas, col) ->
            return model.getCanvasColumnRaw(canvas, col)
        }
        if (SyncPaths.isManifest(path)) {
            return manifestCanvas(path)?.let { manifestContent(it, state) }
        }
        return null
    }

    /**
     * Serialized manifest WITH its version-vector section attached from [s]. Null if the canvas no
     * longer exists. The manifest's OWN sync entry carries the STRUCTURE version vector (svv); live
     * columns carry theirs in `vv`; tombstoned columns go in `deleted` (vv at deletion).
     */
    fun manifestContent(name: CanvasName, s: SyncStateDoc): String? {
        val canvas = model.getCanvas(name) ?: return null
        val doc = CanvasDoc(canvas.name, canvas.parent, canvas.columns, canvas.layouts).encode()
        val svv = s.entries[SyncPaths.manifestPath(root(), name)]?.vv ?: emptyMap()
        // Both sets are scoped to THIS canvas inside ManifestVv.write via the root-aware path parse.
        val byPath = s.entries.filterValues { !it.tombstone }.mapValues { it.value.vv }
        val deleted = s.entries.mapNotNull { (p, e) ->
            if (!e.tombstone) return@mapNotNull null
            val parsed = SyncPaths.parseColumn(p, root()) ?: return@mapNotNull null
            if (parsed.first.normalized() != name.normalized()) return@mapNotNull null
            parsed.second to e.vv
        }.toMap()
        return ManifestVv.write(doc, root(), name, byPath, svv, deleted)
    }
}
