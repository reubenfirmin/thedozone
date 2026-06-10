package tdz.model

/** A column's display mode: rendered cards, or the raw-markdown editor. Persisted per column. */
enum class Mode(val tagIdPrefix: String) {
    CARD("cardcol_"),
    EDITOR("editorcol_");

    fun invert() =
        when (this) {
            CARD -> EDITOR
            EDITOR -> CARD
        }

    /** Sentence-case label for menus etc. ("Card", "Editor") rather than the uppercase enum name. */
    fun displayName() = name.lowercase().replaceFirstChar { it.uppercase() }
}
