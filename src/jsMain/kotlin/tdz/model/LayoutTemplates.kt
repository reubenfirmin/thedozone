package tdz.model

object LayoutTemplates {

    fun canvasFromTemplate(name: CanvasName, parent: CanvasName?, template: LayoutTemplate): Canvas {
        return Canvas(name, parent, template.columns, template.layoutSpecs)
    }

}

enum class LayoutTemplate(val columns: List<ColumnId>, val layoutSpecs: List<ColumnLayoutSpec>) {
    SINGLE_PANE(listOf(0, 1), listOf(
        ColumnLayoutSpec(0, Split.HORIZONTAL, listOf(1))
    ))
}