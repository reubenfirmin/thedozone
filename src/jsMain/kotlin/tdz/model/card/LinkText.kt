package tdz.model.card

interface LinkText: RichText {

    fun isImage(): Boolean

    fun isCanvasLink(): Boolean
}