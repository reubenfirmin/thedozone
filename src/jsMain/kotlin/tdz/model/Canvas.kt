package tdz.model

data class CanvasName(private val orig: String) {
    fun normalized() =
        orig.replace(" ", "-")
            .replace("(", "")
            .replace(")", "")
            .replace("|", "-") // used in serialization
            .replace(".tdz", "")
            .replace("/", "")

    fun display() = normalized().replace("-", " ")
}

/**
 * A column's identity within a canvas. A value class (not a typealias) so a column id can never
 * be confused with a card index — or any other Int — at a call site. Prints as the bare number,
 * so the persisted formats (comma lists, sync paths, DOM ids) are unchanged.
 */
value class ColumnId(val value: Int) {
    /** The next id after this one (allocation walks `columns.last() + 1`; ids may have gaps). */
    fun next() = ColumnId(value + 1)
    override fun toString() = value.toString()
}

data class Canvas(
    val name: CanvasName,
    val parent: CanvasName?,
    // note that there may be gaps if there were deletions.
    // first column is expected to a) be a layout and b) be the root
    val columns: List<ColumnId>,
    val layouts: List<ColumnLayoutSpec>
) {
    val layoutMap = layouts.associateBy { it.columnId }

    fun root() = layoutMap[columns.first()] ?: throw Exception("Expected first column to be a layout")
    fun firstNonLayout() = columns.first { !layoutMap.containsKey(it) }
}

data class ColumnLayoutSpec(
    val columnId: ColumnId,
    /**
     * Which direction this column (layout) is split
     */
    val split: Split,
    /**
     * What columns are laid out inside this column
     */
    val contains: List<ColumnId>
)

enum class Split {
    VERTICAL,
    HORIZONTAL
}