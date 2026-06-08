package tdz.model

object LayoutTemplates {

    fun canvasFromTemplate(name: CanvasName, parent: CanvasName?, template: LayoutTemplate): Canvas {
        return Canvas(name, parent, template.columns, template.layoutSpecs)
    }

}

enum class LayoutTemplate(val columns: List<ColumnId>, val layoutSpecs: List<ColumnLayoutSpec>) {
    SINGLE_PANE(listOf(0, 1), listOf(
        ColumnLayoutSpec(0, Split.HORIZONTAL, listOf(1))
    )),
    TWO_COLUMNS_VERTICAL(listOf(0, 1, 2), listOf(
        ColumnLayoutSpec(0, Split.VERTICAL, listOf(1, 2))
    )),
    THREE_COLUMNS_VERTICAL(listOf(0, 1, 2, 3), listOf(
        ColumnLayoutSpec(0, Split.VERTICAL, listOf(1, 2, 3))
    )),
    TWO_ROWS_HORIZONTAL(listOf(0, 1, 2), listOf(
        ColumnLayoutSpec(0, Split.HORIZONTAL, listOf(1, 2))
    ))
}