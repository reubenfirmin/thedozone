package tdz.model

/**
 * Per-column visual overrides. Null/blank fields mean "no override" (fall back to the theme). Stored
 * in the column's own markdown file (a header line) so it round-trips and syncs with column content.
 */
data class ColumnStyle(val wallpaper: String?, val color: String?)
