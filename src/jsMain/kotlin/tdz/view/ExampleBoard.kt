package tdz.view

import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.rootCanvas
import tdz.view.theme.Theme

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
    // A board may request a UI theme to be PREVIEWED while it's shown (e.g. a flashcard deck that
    // wants dark-on-white). [ExampleLoader] applies it without persisting and restores the user's
    // theme when the tour resets. Null = leave the user's theme alone.
    val theme: Theme? = null,
) {
    /** This board as a [Canvas]. */
    fun canvas(): Canvas = Canvas(name, parent, columns, layouts)

    /** The content (non-layout) columns, in declared order. */
    fun contentColumns(): List<ColumnId> {
        val layoutIds = layouts.map { it.columnId }.toSet()
        return columns.filterNot { it in layoutIds }
    }
}
