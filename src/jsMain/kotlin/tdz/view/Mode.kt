package tdz.view

enum class Mode(val tagIdPrefix: String) {
    CARD("cardcol_"),
    EDITOR("editorcol_");

    fun invert() =
        when (this) {
            CARD -> EDITOR
            EDITOR -> CARD
        }
}