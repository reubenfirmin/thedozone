package tdz.model.card

/**
 * A link to an external site, part of a CardText segment.
 */
data class ExternalLink(val url: String, val label: String): LinkText {

    override fun isImage() = false

    override fun isCanvasLink() = false

    override fun toMarkdown() = "[${url} (${label})]"
}
