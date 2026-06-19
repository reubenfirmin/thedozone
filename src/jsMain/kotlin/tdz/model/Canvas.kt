package tdz.model

import kotlin.random.Random

data class CanvasName(private val orig: String) {
    /** Per-segment cleanup, segments rejoined by "/". Empty/edge/duplicate slashes collapse. The
     *  whole value IS the board's path; root is the empty path. Casing is preserved (the existing
     *  sync-folder and URL formats are case-sensitive). */
    fun normalized(): String =
        orig.split("/")
            .map { seg ->
                seg.replace(" ", "-")
                    .replace("(", "")
                    .replace(")", "")
                    .replace("|", "-")      // used in serialization
                    .replace(".tdz", "")
            }
            .filter { it.isNotEmpty() }
            .joinToString("/")

    fun segments(): List<String> = normalized().split("/").filter { it.isNotEmpty() }
    fun isRoot(): Boolean = segments().isEmpty()
    /** Last segment, in display form (dashes -> spaces). Empty for root. */
    fun leaf(): String = segments().lastOrNull()?.replace("-", " ") ?: ""
    /** This path with its last segment removed (root when there is one segment or fewer). */
    fun parentName(): CanvasName = CanvasName(segments().dropLast(1).joinToString("/"))
    fun child(segment: String): CanvasName = CanvasName((segments() + segment).joinToString("/"))
    /** Whole path in display form -- used by current UI until later tasks make listings path-aware. */
    fun display(): String = normalized().replace("-", " ")
}

/**
 * A column's identity within a canvas. A value class (not a typealias) so a column id can never be
 * confused with a card index — or any other value — at a call site.
 *
 * The id is an OPAQUE STRING, allocated by [random]. It is deliberately NOT an incrementing integer:
 * two devices each allocating `max+1` mint the SAME id for DIFFERENT columns when they add
 * concurrently, and sync (which keys by path, `col-<id>.md`) would then merge two unrelated columns.
 * Random opaque ids can't collide across devices. Legacy boards' numeric ids remain valid strings
 * ("0", "1", …) and coexist unchanged — only newly-allocated ids are opaque. The alphabet is
 * `[A-Za-z0-9]` only: `-` `:` `,` `|` `/` `.` are reserved by the DOM/markdown/manifest/path formats.
 */
value class ColumnId(val value: String) {
    override fun toString() = value

    companion object {
        /** The root layout container. Fixed at "0": it's the per-board container matched by
         *  [Canvas.columns]`.first()`, every existing persisted board already uses "0", and both
         *  devices must agree on it — so it stays a constant, never randomized. */
        val ROOT = ColumnId("0")

        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** A fresh opaque content/layout id not in [existing] (guards the astronomically rare local
         *  collision; cross-device collision over a 10-char alphanumeric space is negligible). */
        fun random(existing: Set<ColumnId> = emptySet()): ColumnId {
            while (true) {
                val candidate = ColumnId(buildString { repeat(10) { append(ALPHABET[Random.nextInt(ALPHABET.length)]) } })
                if (candidate !in existing) return candidate
            }
        }

        /** [n] distinct fresh opaque ids, none in [existing]. */
        fun randomMany(n: Int, existing: Set<ColumnId> = emptySet()): List<ColumnId> {
            val used = existing.toMutableSet()
            return List(n) { random(used).also { used += it } }
        }
    }
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
        // Fresh opaque root + (if needed) content id, guaranteed not to clash with any surviving id.
        // Repair is a rare recovery path; non-deterministic ids across devices are acceptable here.
        val existing = (columns + layouts.flatMap { it.contains + it.columnId }).toSet()
        val rootId = ColumnId.random(existing)
        val finalContent = content.ifEmpty { listOf(ColumnId.random(existing + rootId)) }
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