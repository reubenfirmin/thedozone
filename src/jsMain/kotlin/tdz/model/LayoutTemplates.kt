package tdz.model

object LayoutTemplates {

    fun canvasFromTemplate(name: CanvasName, parent: CanvasName?, template: LayoutTemplate): Canvas {
        return Canvas(name, parent, template.columns, template.layoutSpecs)
    }

}

private fun cols(vararg ids: Int) = ids.map(::ColumnId)

enum class LayoutTemplate(val columns: List<ColumnId>, val layoutSpecs: List<ColumnLayoutSpec>) {
    SINGLE_PANE(cols(0, 1), listOf(
        ColumnLayoutSpec(ColumnId(0), Split.HORIZONTAL, cols(1))
    )),
    TWO_COLUMNS_VERTICAL(cols(0, 1, 2), listOf(
        ColumnLayoutSpec(ColumnId(0), Split.VERTICAL, cols(1, 2))
    )),
    THREE_COLUMNS_VERTICAL(cols(0, 1, 2, 3), listOf(
        ColumnLayoutSpec(ColumnId(0), Split.VERTICAL, cols(1, 2, 3))
    )),
    TWO_ROWS_HORIZONTAL(cols(0, 1, 2), listOf(
        ColumnLayoutSpec(ColumnId(0), Split.HORIZONTAL, cols(1, 2))
    ))
}