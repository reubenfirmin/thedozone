package tdz.model.card

import tdz.model.CanvasName

/**
 * A link to a canvas, part of a CardText segment.
 */
data class CanvasLink(var canvasName: CanvasName, val label: String): LinkText {

    override fun isImage() = false

    override fun isCanvasLink() = true

    override fun toMarkdown() = "[${canvasName.normalized()} (${label})]"
}
