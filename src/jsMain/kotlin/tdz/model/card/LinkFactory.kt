package tdz.model.card

import org.w3c.dom.url.URL
import tdz.mapLog
import tdz.model.CanvasName
import zoned.framework.Debugger.debug

val imageSuffices = setOf(".jpg", ".JPG", ".png", ".PNG", ".gif", ".GIF", ".svg", ".SVG", "jpeg", "JPEG", "webp", "WEBP")

/** Schemes we treat as openable web links when a token parses as an absolute URL in the browser. */
private val webSchemes = setOf("http:", "https:", "mailto:", "tel:", "ftp:", "ftps:")

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

        val trimmed = link.trim()
        val label = label.trim().ifEmpty { trimmed }
        return if (isWebLink(trimmed)) {
            if (trimmed.length > 4 && trimmed.substring(trimmed.length - 4, trimmed.length) in imageSuffices) {
                ImageLink(trimmed, label.shorten())
            } else {
                ExternalLink(trimmed, label.shorten())
            }
        } else {
            val mapLink = parseMapString("$trimmed($label)")

            mapLink ?: CanvasLink(CanvasName(trimmed), label.shorten())
        }
    }

    /**
     * A bracketed token is a web link when the browser parses it as an absolute URL with a known web
     * scheme (`https:`, `mailto:`, ...), or it names a host explicitly (`www.`), or it is a path /
     * resource (`/`). A bare name with none of these markers (e.g. `google.com` or `My Canvas`) is a
     * link to another canvas. Using the platform `URL` parser rather than string matching means we
     * also accept `mailto:`/`tel:` and reject malformed inputs such as `https://` with no host.
     */
    private fun isWebLink(candidate: String): Boolean {
        if (parseAbsoluteWebUrl(candidate) != null) return true
        if (candidate.startsWith("www.")) return true
        if (candidate.contains("/")) return true
        return false
    }

    private fun parseAbsoluteWebUrl(candidate: String): URL? =
        try {
            URL(candidate).takeIf { it.protocol.lowercase() in webSchemes }
        } catch (e: Throwable) {
            null
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
