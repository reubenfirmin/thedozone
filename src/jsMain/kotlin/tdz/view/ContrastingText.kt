package tdz.view

/** Dark text for contrast on a light band (also the fallback when a color is unparseable). */
private const val CONTRAST_DARK = "#1d1b20"

/** Light text for contrast on a dark band. */
private const val CONTRAST_LIGHT = "#ffffff"

/**
 * Black or white, whichever reads better on [hex] (`#rgb`/`#rrggbb`), by perceived luminance. A
 * pure color utility (hex parse + black-vs-white decision) — independent of column layout, so it
 * lives on its own rather than inside [CanvasColumns]. Unparseable input falls back to dark text
 * (assumes a light band).
 */
fun contrastingText(hex: String): String {
    val h = hex.removePrefix("#")
    val (r, g, b) = when (h.length) {
        3 -> Triple(h[0].digitToIntOrNull(16), h[1].digitToIntOrNull(16), h[2].digitToIntOrNull(16))
            .let { (rr, gg, bb) -> Triple(rr?.times(17), gg?.times(17), bb?.times(17)) }
        else -> Triple(
            h.take(2).toIntOrNull(16), h.drop(2).take(2).toIntOrNull(16), h.drop(4).take(2).toIntOrNull(16),
        )
    }
    if (r == null || g == null || b == null) return CONTRAST_DARK   // unparseable → assume light band
    val luma = 0.299 * r + 0.587 * g + 0.114 * b
    return if (luma < 140) CONTRAST_LIGHT else CONTRAST_DARK
}
