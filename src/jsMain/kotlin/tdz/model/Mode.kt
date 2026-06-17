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
}
