package tdz.view

import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.rootCanvas

/**
 * A self-contained example board the intro wizard can drop onto the canvas: which canvas it
 * populates ([name]/[parent]), its column/layout structure, and the Markdown for each content
 * column. Pure data — [ExampleLoader] applies it. Most examples populate the root board; a
 * multi-board scene also carries named child boards.
 */
data class ExampleBoard(
    val title: String,
    val tagline: String,
    val columns: List<ColumnId>,
    val layouts: List<ColumnLayoutSpec>,
    val content: Map<ColumnId, String>,
    val name: CanvasName = rootCanvas,
    val parent: CanvasName? = null,
) {
    /** This board as a [Canvas]. */
    fun canvas(): Canvas = Canvas(name, parent, columns, layouts)

    /** The content (non-layout) columns, in declared order. */
    fun contentColumns(): List<ColumnId> {
        val layoutIds = layouts.map { it.columnId }.toSet()
        return columns.filterNot { it in layoutIds }
    }
}
