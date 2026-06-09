package tdz.sync

import tdz.model.CanvasName
import kotlin.js.Json
import kotlin.js.json

/**
 * Reads/writes the sync-owned `"vv"` section of a canvas manifest JSON ([CanvasDoc] handles the rest).
 * Shape: `"vv": { "<columnId>": { "<deviceId>": <count> } }`. Exposed keyed by full column sync path so
 * the engine/controller can line it up with [SyncEntry] without knowing the manifest layout.
 */
object ManifestVv {

    /** column path -> version vector, parsed from [manifest]'s `vv` (empty if absent). */
    fun read(manifest: Json, root: String, canvas: CanvasName): Map<String, VersionVector> {
        val vvObj = (manifest["vv"] as Json?) ?: return emptyMap()
        val out = HashMap<String, VersionVector>()
        (js("Object").keys(vvObj) as Array<String>).forEach { colId ->
            val path = SyncPaths.columnPath(root, canvas, colId.toInt())
            out[path] = vvFromJson(vvObj[colId] as Json)
        }
        return out
    }

    /** The version vector for a single [columnId] in [manifest]'s `vv` (empty if absent). */
    fun readOne(manifest: Json, columnId: Int): VersionVector {
        val vvObj = (manifest["vv"] as Json?) ?: return emptyMap()
        val one = (vvObj.asDynamic()[columnId.toString()] as Json?) ?: return emptyMap()
        return vvFromJson(one)
    }

    /** Attach a `vv` object to [manifest] built from [byPath] (only paths under this canvas are used). */
    fun write(manifest: Json, root: String, canvas: CanvasName, byPath: Map<String, VersionVector>) {
        val vvObj = json()
        byPath.forEach { (path, vv) ->
            val parsed = SyncPaths.parseColumn(path) ?: return@forEach
            if (parsed.first.normalized() != canvas.normalized()) return@forEach
            vvObj[parsed.second.toString()] = vvToJson(vv)
        }
        manifest["vv"] = vvObj
    }
}
