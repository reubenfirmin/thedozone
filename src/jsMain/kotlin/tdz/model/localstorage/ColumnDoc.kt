package tdz.model.localstorage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import tdz.model.CanvasName
import tdz.model.ColumnId

@Serializable
private data class ColumnWire(
    @SerialName("column") val columnLabel: String,
    @SerialName("canvas") val canvasName: String,
    val value: String,
)

/**
 * The FULL localStorage keys holding one canvas's data — manifest, column content, column modes.
 * The single source of truth for "which keys belong to canvas X" (used by the per-canvas backup
 * restore); key shapes must stay in lockstep with [LocalStorageModel]'s store/retrieve calls.
 */
object CanvasStorageKeys {
    fun manifest(name: CanvasName): String = Namespace.NEWCANVAS.toString() + name.normalized()
    fun column(name: CanvasName, col: ColumnId): String =
        Namespace.COLUMN.toString() + ColumnDoc.toKey(name, ColumnDoc.toLabel(col))
    fun columnMode(name: CanvasName, col: ColumnId): String =
        Namespace.COLUMN_MODE.toString() + "${name.normalized()}-$col"
}

internal data class ColumnDoc(val columnLabel: String, val canvasName: CanvasName, val value: String): HasKey {

    override val key: String
        get() = toKey(canvasName, columnLabel)

    override fun encode(): String =
        storageJson.encodeToString(ColumnWire(columnLabel, canvasName.normalized(), value))

    companion object {
        fun toLabel(columnId: ColumnId): String {
            return "$columnId"
        }

        fun toKey(canvas: CanvasName, columnLabel: String) = canvas.normalized() + "_" + columnLabel

        fun decode(raw: String): ColumnDoc {
            val wire = storageJson.decodeFromString<ColumnWire>(raw)
            return ColumnDoc(wire.columnLabel, CanvasName(wire.canvasName), wire.value)
        }
    }
}
