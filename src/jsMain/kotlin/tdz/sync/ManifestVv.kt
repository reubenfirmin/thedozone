package tdz.sync

import kotlinx.serialization.encodeToString
import tdz.model.CanvasName
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
            SyncPaths.columnPath(root, canvas, colId.toInt()) to vector
        }
    }

    /** The version vector for a single [columnId] in [manifestContent]'s `vv` (empty if absent/corrupt). */
    fun readOne(manifestContent: String, columnId: Int): VersionVector =
        decodeVv(manifestContent)?.get(columnId.toString()) ?: emptyMap()

    /**
     * Returns [manifestContent] with a `vv` section built from [byPath] (only paths under this
     * canvas are used). Throws on an unparseable manifest — the caller produced it.
     */
    fun write(manifestContent: String, root: String, canvas: CanvasName, byPath: Map<String, VersionVector>): String {
        val wire = storageJson.decodeFromString<CanvasWire>(manifestContent)
        val vv = buildMap {
            byPath.forEach { (path, vector) ->
                val parsed = SyncPaths.parseColumn(path) ?: return@forEach
                if (parsed.first.normalized() != canvas.normalized()) return@forEach
                put(parsed.second.toString(), vector)
            }
        }
        return storageJson.encodeToString(wire.copy(vv = vv))
    }

    private fun decodeVv(manifestContent: String): Map<String, Map<String, Long>>? =
        runCatching { storageJson.decodeFromString<CanvasWire>(manifestContent).vv }.getOrNull()
}
