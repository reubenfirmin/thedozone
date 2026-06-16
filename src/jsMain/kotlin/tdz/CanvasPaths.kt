package tdz

/**
 * Pure path-algebra and navigation functions for canvas (board) paths.
 *
 * These functions are intentionally free of any Index/Model/DOM dependencies — they take
 * [CanvasName] values and strings and return new ones, making them trivially unit-testable
 * and safe to call from any layer.
 */

import tdz.model.CanvasName

/** A board's URL is just its path. Root -> "/". Pure; unit-tested. */
fun canvasUrl(name: CanvasName): String = "/" + name.normalized()

/** A board's ancestry as cumulative paths, root-first, INCLUSIVE of the board (root excluded — it
 *  is the empty path). E.g. "a/b/c" -> ["a", "a/b", "a/b/c"]. Pure; unit-tested. */
fun ancestry(name: CanvasName): List<CanvasName> {
    val segs = name.segments()
    return segs.indices.map { i -> CanvasName(segs.subList(0, i + 1).joinToString("/")) }
}

/** Resolve a canvas-link's raw [target] relative to [current]:
 *  `~`/`/` -> root, `..` -> parent, a leading-slash path -> absolute from root, anything else ->
 *  relative to current (single or multi segment). Pure; unit-tested. */
fun resolveCanvasLinkTarget(current: CanvasName, target: String): CanvasName {
    val t = target.trim()
    return when {
        t == "~" || t == "/" -> rootCanvas
        t == ".." -> current.parentName()
        t.startsWith("/") -> CanvasName(t)                                   // absolute (normalize drops leading /)
        else -> CanvasName(current.normalized() + "/" + t)                   // relative; root.normalized() is "" -> "/t" -> "t"
    }
}
