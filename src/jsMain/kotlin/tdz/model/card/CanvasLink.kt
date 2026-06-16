package tdz.model.card

import tdz.model.CanvasName

/**
 * A link to a canvas, part of a CardText segment.
 *
 * [target] is the raw bracket text as typed by the user (e.g. `".."`, `"~"`, `"/a/b/c"`,
 * `"sub/child"`, `"My Board"`). Resolution of relative vs. absolute must go through
 * [tdz.resolveCanvasLinkTarget] using [target], not through [canvasName], because
 * `CanvasName` normalisation collapses the leading `/` that distinguishes absolute paths.
 */
data class CanvasLink(val target: String, val label: String) : LinkText {

    override fun isImage() = false

    override fun isCanvasLink() = true

    /** Normalized path form (loses the leading-slash absolute marker — for display/keys only;
     *  navigation must resolve from [target] via resolveCanvasLinkTarget). */
    val unresolvedName: CanvasName get() = CanvasName(target)

    override fun toMarkdown() = "[$target ($label)]"
}
