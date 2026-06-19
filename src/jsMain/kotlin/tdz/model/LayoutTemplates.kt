package tdz.model

object LayoutTemplates {

    /**
     * Instantiate [template] as a board. Every NON-root column gets a FRESH OPAQUE id (D1a): the
     * template's `"1"`/`"2"`… are scaffolding, not stable identities — if two devices built the same
     * board from those literal ids and both synced, the per-id column merge would jam unrelated cards
     * together. Remapping at creation makes each device's content columns unique. Root stays
     * [ColumnId.ROOT] (the per-board container, agreed across devices).
     */
    fun canvasFromTemplate(name: CanvasName, parent: CanvasName?, template: LayoutTemplate): Canvas {
        val remap = remapNonRoot(template.columns)
        return Canvas(name, parent,
            template.columns.map { remap.getValue(it) },
            template.layoutSpecs.map { spec ->
                ColumnLayoutSpec(remap.getValue(spec.columnId), spec.split, spec.contains.map { remap.getValue(it) })
            })
    }

    /** Map each id in [columns] to a fresh opaque id, except [ColumnId.ROOT] which maps to itself. */
    private fun remapNonRoot(columns: List<ColumnId>): Map<ColumnId, ColumnId> {
        val used = mutableSetOf(ColumnId.ROOT)
        return columns.associateWith { id ->
            if (id == ColumnId.ROOT) ColumnId.ROOT else ColumnId.random(used).also { used += it }
        }
    }

}

private fun cols(vararg ids: String) = ids.map(::ColumnId)

// Template ids are SCAFFOLDING — "0" (root) plus "1".. for content — remapped to opaque ids per
// instantiation by [LayoutTemplates.canvasFromTemplate]; never persisted as-is.
enum class LayoutTemplate(val columns: List<ColumnId>, val layoutSpecs: List<ColumnLayoutSpec>) {
    SINGLE_PANE(cols("0", "1"), listOf(
        ColumnLayoutSpec(ColumnId.ROOT, Split.HORIZONTAL, cols("1"))
    )),
    TWO_COLUMNS_VERTICAL(cols("0", "1", "2"), listOf(
        ColumnLayoutSpec(ColumnId.ROOT, Split.VERTICAL, cols("1", "2"))
    )),
    THREE_COLUMNS_VERTICAL(cols("0", "1", "2", "3"), listOf(
        ColumnLayoutSpec(ColumnId.ROOT, Split.VERTICAL, cols("1", "2", "3"))
    )),
    TWO_ROWS_HORIZONTAL(cols("0", "1", "2"), listOf(
        ColumnLayoutSpec(ColumnId.ROOT, Split.HORIZONTAL, cols("1", "2"))
    ))
}