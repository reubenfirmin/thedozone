package tdz.model.localstorage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Split

/**
 * The persisted shape of a canvas — used verbatim for the localStorage doc, the synced manifest
 * file (`_canvas.json`, which adds the sync-owned [vv] section via [tdz.sync.ManifestVv]), and the
 * `.tdz` download header. One wire class so the three can never drift apart.
 */
@Serializable
internal data class CanvasWire(
    val name: String,
    val parentId: String? = null,
    val columns: String,
    val layouts: String,
    /** Sync-owned version vectors: columnId -> (deviceId -> count). Never written by the app side. */
    val vv: Map<String, Map<String, Long>>? = null,
    /** Sync-owned STRUCTURE version vector: deviceId -> count, bumped on every layout/column-set
     *  change. Lets the layout be resolved by causality (newer wins) like content, instead of a
     *  special "remote shape wins" rule. Never written by the app side. */
    val svv: Map<String, Long>? = null,
    /** Sync-owned tombstones: columnId -> the column's CONTENT version vector at the moment it was
     *  deleted. Lets a concurrent delete win over a stale re-add (additive merge alone resurrects
     *  deleted columns), while a concurrent EDIT the deleter never saw still keeps the column.
     *  Never written by the app side. */
    val deleted: Map<String, Map<String, Long>>? = null,
)

data class CanvasDoc (
    val name: CanvasName,
    val parentName: CanvasName?,
    val columns: List<ColumnId>,
    val layouts: List<ColumnLayoutSpec>): HasKey {

    init {
        require(name != parentName) {
            "Canvas $name was made with parent $parentName"
        }
    }

    override val key: String
        get() = name.normalized()

    override fun encode(): String = storageJson.encodeToString(
        CanvasWire(
            name = name.normalized(),
            parentId = parentName?.normalized(),
            columns = columns.joinToString(","),
            layouts = layouts.joinToString("||") {
                ColumnLayoutSpecDoc(it.columnId, it.split, it.contains).encode()
            },
        )
    )

    companion object {
        fun decode(raw: String): CanvasDoc = fromWire(storageJson.decodeFromString(raw))

        internal fun fromWire(wire: CanvasWire) = CanvasDoc(
            CanvasName(wire.name),
            wire.parentId?.let { CanvasName(it) },
            wire.columns.split(",").filter { it.isNotBlank() }.map { ColumnId(it) },
            wire.layouts.split("||")
                .mapNotNull { ColumnLayoutSpecDoc.decode(it) }
                .map { ColumnLayoutSpec(it.columnId, it.split, it.contains) },
        )
    }
}

data class ColumnLayoutSpecDoc(
    val columnId: ColumnId,
    val split: Split,
    val contains: List<ColumnId>
) {

    /** Pipe-delimited, not JSON — historical format kept for storage compatibility. */
    fun encode(): String {
        return "$columnId|${split.name}|${contains.joinToString(",")}"
    }

    companion object {
        fun decode(raw: String): ColumnLayoutSpecDoc? {
            val parts = raw.split("|")
            // A legacy entry led with a redundant canvasName (the canvas name comes from the
            // enclosing doc); tolerate it. 4 fields = legacy (drop the name), 3 = current.
            val fields = if (parts.size >= 4) parts.drop(1) else parts
            if (fields.size < 3) {
                console.error("Could not parse: $raw")
                return null
            }
            return runCatching {
                ColumnLayoutSpecDoc(
                    ColumnId(fields[0]),
                    Split.valueOf(fields[1]),
                    fields[2].split(",").filter { it.isNotBlank() }.map { ColumnId(it) }
                )
            }.getOrElse {
                console.error("Could not parse: $raw")
                null
            }
        }
    }
}
