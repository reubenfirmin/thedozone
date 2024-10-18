package tdz.model.localstorage

import tdz.model.CanvasName
import kotlin.js.Json
import kotlin.js.json

internal data class ColumnDoc(val columnLabel: String, val canvasName: CanvasName, val value: String): HasKey {

    override val key: String
        get() = toKey(canvasName, columnLabel)

    override fun toJson(): Json {
        return json("column" to columnLabel,
            "canvas" to canvasName.normalized(),
            "value" to value)
    }

    companion object {
        fun toLabel(columnId: Int): String {
            return "$columnId"
        }

        fun toKey(canvas: CanvasName, columnLabel: String) = canvas.normalized() + "_" + columnLabel

        fun fromJson(json: Json): ColumnDoc {
            return ColumnDoc(
                json["column"] as String,
                CanvasName(json["canvas"] as String),
                json["value"] as String)
        }
    }
}