package tdz.model.card

/**
 * A link to an image, renders as an image.
 */
data class ImageLink(val url: String, val label: String): LinkText {

    override fun isImage() = true

    override fun isCanvasLink() = false

    override fun toMarkdown() = "[${url} (${label})]"
}
