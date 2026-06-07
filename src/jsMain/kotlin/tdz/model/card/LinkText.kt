package tdz.model.card

sealed interface LinkText: RichText {

    fun isImage(): Boolean

    fun isCanvasLink(): Boolean
}