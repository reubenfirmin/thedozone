package tdz.model

/**
 * The identity of a UI theme (global, persisted). A value class — not a raw String — so a theme
 * name can't be confused with any other string at a call site. Construct with `ThemeName(x)` and
 * read the underlying name via [value].
 */
value class ThemeName(val value: String) {
    override fun toString() = value
}
