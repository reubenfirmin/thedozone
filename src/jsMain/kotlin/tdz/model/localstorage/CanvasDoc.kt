package tdz.model.localstorage

import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Split
import kotlin.js.Json
import kotlin.js.json

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

    override fun toJson(): Json {
        return json(
            "name" to name.normalized(),
            "parentId" to parentName?.normalized(),
            "columns" to columns.joinToString(","),
            "layouts" to layouts.joinToString("||") {
                ColumnLayoutSpecDoc(name, it.columnId, it.split, it.contains).toJson()
            })
    }

    companion object {
        fun fromJson(json: Json): CanvasDoc {
            val conv = CanvasDoc(
                CanvasName(json["name"] as String),
                (json["parentId"] as String?)?.let { CanvasName(it) },
                (json["columns"] as String).split(",").map { it.toInt() },
                (json["layouts"] as String).split("||")
                    .mapNotNull { ColumnLayoutSpecDoc.fromJson(it) }
                    .map { ColumnLayoutSpec(it.columnId, it.split, it.contains ) }
            )
            return conv
        }
    }
}

data class ColumnLayoutSpecDoc(
    val canvasName: CanvasName,
    val columnId: ColumnId,
    val split: Split,
    val contains: List<ColumnId>
) {

    fun toJson(): String {
        return "${canvasName.normalized()}|$columnId|${split.name}|${contains.joinToString(",")}"
    }

    companion object {
        fun fromJson(jsonStr: String): ColumnLayoutSpecDoc? {
            val parts = jsonStr.split("|")
            if (parts.size < 4) {
                console.error("Could not parse: $jsonStr")
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
