package tdz.model

/** A column's display mode: rendered cards, or the raw-markdown editor. Persisted per column. */
enum class Mode {
    CARD,
    EDITOR;

    fun invert() =
        when (this) {
            CARD -> EDITOR
            EDITOR -> CARD
        }

    /** Sentence-case label for menus etc. ("Card", "Editor") rather than the uppercase enum name. */
    fun displayName() = name.lowercase().replaceFirstChar { it.uppercase() }
}
