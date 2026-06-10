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
                ColumnLayoutSpecDoc(name, it.columnId, it.split, it.contains).encode()
            },
        )
    )

    companion object {
        fun decode(raw: String): CanvasDoc = fromWire(storageJson.decodeFromString(raw))

        internal fun fromWire(wire: CanvasWire) = CanvasDoc(
            CanvasName(wire.name),
            wire.parentId?.let { CanvasName(it) },
            wire.columns.split(",").map { it.toInt() },
            wire.layouts.split("||")
                .mapNotNull { ColumnLayoutSpecDoc.decode(it) }
                .map { ColumnLayoutSpec(it.columnId, it.split, it.contains) },
        )
    }
}

data class ColumnLayoutSpecDoc(
    val canvasName: CanvasName,
    val columnId: ColumnId,
    val split: Split,
    val contains: List<ColumnId>
) {

    /** Pipe-delimited, not JSON — historical format kept for storage compatibility. */
    fun encode(): String {
        return "${canvasName.normalized()}|$columnId|${split.name}|${contains.joinToString(",")}"
    }

    companion object {
        fun decode(raw: String): ColumnLayoutSpecDoc? {
            val parts = raw.split("|")
            if (parts.size < 4) {
                console.error("Could not parse: $raw")
                return null
            }
            return ColumnLayoutSpecDoc(
                CanvasName(parts[0]),
                parts[1].toInt(),
                Split.valueOf(parts[2]),
                (parts[3]).split(",").map { it.toInt() }
            )
        }
    }
}
