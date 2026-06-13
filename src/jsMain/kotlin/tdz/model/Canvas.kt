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
    /** The next id after this one. (ColumnSplit allocates from max+1, not last()+1; ids have gaps.) */
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

    /**
     * A render-safe layout: a root layout exists, the tree has no cycles and every `contains` entry
     * resolves, and at least one CONTENT (non-layout) column is reachable. A corrupt synced manifest
     * (cyclic layout) or a board deleted down to nothing fails this.
     */
    fun isLayoutSound(): Boolean {
        if (columns.isEmpty() || !layoutMap.containsKey(columns.first())) return false
        val seen = mutableSetOf<ColumnId>()
        var contentLeaves = 0
        fun walk(id: ColumnId): Boolean {
            if (!seen.add(id)) return false                              // revisit => cycle / shared node
            val layout = layoutMap[id] ?: run { contentLeaves++; return true }   // content leaf
            return layout.contains.all { walk(it) }
        }
        return walk(columns.first()) && contentLeaves >= 1
    }

    /**
     * A guaranteed-renderable copy. If [isLayoutSound] is false (cyclic, rootless, or zero content
     * columns), rebuild a flat single-row layout over the existing content columns — preserving
     * their card data — or one fresh empty column if there are none. This is what lets a board that
     * was corrupted (or deleted to nothing) before the guards existed heal itself on next display
     * instead of being permanently stuck with no columns. Sound canvases are returned unchanged.
     */
    fun repaired(): Canvas {
        if (isLayoutSound()) return this
        val content = columns.filterNot { layoutMap.containsKey(it) }
        val maxId = (columns.map { it.value } +
            layouts.flatMap { it.contains.map { c -> c.value } + it.columnId.value }).maxOrNull() ?: -1
        val rootId = ColumnId(maxId + 1)
        val finalContent = content.ifEmpty { listOf(ColumnId(maxId + 2)) }
        return Canvas(name, parent, listOf(rootId) + finalContent,
            listOf(ColumnLayoutSpec(rootId, Split.VERTICAL, finalContent)))
    }
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