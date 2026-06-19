package tdz.sync

import kotlinx.serialization.encodeToString
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.localstorage.CanvasWire
import tdz.model.localstorage.storageJson

/**
 * Reads/writes the sync-owned `"vv"` section of a canvas manifest ([tdz.model.localstorage.CanvasDoc]
 * handles the rest; both speak [CanvasWire] so the formats cannot drift). Shape:
 * `"vv": { "<columnId>": { "<deviceId>": <count> } }`. Exposed keyed by full column sync path so
 * the engine/controller can line it up with [tdz.model.localstorage.SyncEntry] without knowing the
 * manifest layout.
 */
object ManifestVv {

    /** column path -> version vector, parsed from [manifestContent]'s `vv` (empty if absent/corrupt). */
    fun read(manifestContent: String, root: String, canvas: CanvasName): Map<String, VersionVector> {
        val vv = decodeVv(manifestContent) ?: return emptyMap()
        return vv.entries.associate { (colId, vector) ->
            SyncPaths.columnPath(root, canvas, ColumnId(colId)) to vector
        }
    }

    /** The version vector for a single [columnId] in [manifestContent]'s `vv` (empty if absent/corrupt). */
    fun readOne(manifestContent: String, columnId: ColumnId): VersionVector =
        decodeVv(manifestContent)?.get(columnId.toString()) ?: emptyMap()

    /**
     * Returns [manifestContent] with a `vv` section built from [byPath] (only paths under this
     * canvas are used) and an `svv` STRUCTURE vector. Throws on an unparseable manifest — the caller
     * produced it.
     */
    fun write(
        manifestContent: String,
        root: String,
        canvas: CanvasName,
        byPath: Map<String, VersionVector>,
        svv: VersionVector = emptyMap(),
        deleted: Map<ColumnId, VersionVector> = emptyMap(),
    ): String {
        val wire = storageJson.decodeFromString<CanvasWire>(manifestContent)
        val vv = buildMap {
            byPath.forEach { (path, vector) ->
                // Parse WITH the root so nested-board paths (e.g. parent/child) still match `canvas`;
                // the no-root overload returns only the last segment and would drop every vv entry.
                val parsed = SyncPaths.parseColumn(path, root) ?: return@forEach
                if (parsed.first.normalized() != canvas.normalized()) return@forEach
                put(parsed.second.toString(), vector)
            }
        }
        val tombstones = deleted.mapKeys { it.key.toString() }.ifEmpty { null }
        return storageJson.encodeToString(wire.copy(vv = vv, svv = svv.ifEmpty { null }, deleted = tombstones))
    }

    /** The STRUCTURE version vector from [manifestContent]'s `svv` (empty if absent/corrupt). */
    fun readSvv(manifestContent: String): VersionVector =
        runCatching { storageJson.decodeFromString<CanvasWire>(manifestContent).svv }.getOrNull() ?: emptyMap()

    /** Tombstones (columnId -> vv at deletion) from [manifestContent]'s `deleted` (empty if absent/corrupt). */
    fun readDeleted(manifestContent: String): Map<ColumnId, VersionVector> =
        runCatching { storageJson.decodeFromString<CanvasWire>(manifestContent).deleted }.getOrNull()
            ?.mapKeys { ColumnId(it.key) } ?: emptyMap()

    private fun decodeVv(manifestContent: String): Map<String, Map<String, Long>>? =
        runCatching { storageJson.decodeFromString<CanvasWire>(manifestContent).vv }.getOrNull()
}
