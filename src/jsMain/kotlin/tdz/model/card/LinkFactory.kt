package tdz.model.card

import tdz.mapLog
import tdz.model.CanvasName
import zoned.framework.Debugger.debug

val imageSuffices = setOf(".jpg", ".JPG", ".png", ".PNG", ".gif", ".GIF", ".svg", ".SVG", "jpeg", "JPEG", "webp", "WEBP")

/**
 * @param link - the link to something
 * @param label - what should be displayed
 */
class LinkFactory(val link: String, val label: String) {

    fun parseMapString(input: String): MapLink? {
        val regex = Regex("map\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)" +
                "(?:,\\s*(-?\\d+(?:\\.\\d+)?))?" +
                "(?:,\\s*(-?\\d+(?:\\.\\d+)?))?\\)")
        val matchResult = regex.find(input)
        return if (matchResult != null) {
            debug("Found a map! $input", mapLog)
            val (lat, long, zoom, height) = matchResult.destructured
            MapLink(
                lat.toDouble(),
                long.toDouble(),
                zoom.toIntOrNull(),
                height.toIntOrNull()
            )
        } else {
            debug("Not a map! $input", mapLog)
            null
        }
    }
    /**
     * If external (contains /), determine if it's an image, and if so return as an ImageLink; if not return as
     * ExternalLink.
     *
     * If not external, return as CanvasLink; first assume it's a new canvas, but kick off searches for absolute
     * or relative path fragments.
     */
    fun resolve(): LinkText {

        val label = label.trim().ifEmpty { link.trim() }
        return if (link.contains("/")) {
            if (link.length > 4 && link.substring(link.length - 4, link.length) in imageSuffices) {
                ImageLink(link, label.shorten())
            } else {
                ExternalLink(link, label.shorten())
            }
        } else {
            val trimmed = link.trim()
            val mapLink = parseMapString("$trimmed($label)")

            mapLink ?: CanvasLink(CanvasName(trimmed), label.shorten())
        }
    }
}

private fun String.shorten() =
    if (this.length > 30) {
        val numToRemove = this.length - 30
        val mid = this.length / 2
        val first = this.substring(0 .. (mid - (numToRemove/2)))
        val second = this.substring((mid + (numToRemove/2)) .. this.length)
        val candidate = "$first..$second"
        if (candidate.length > this.length) {
            this
        } else {
            candidate
        }
    } else {
        this
    }
