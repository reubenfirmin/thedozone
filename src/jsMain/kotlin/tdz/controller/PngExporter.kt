package tdz.controller

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.asList
import web.dom.Element as WebElement
import zoned.framework.libs.HtmlToPngOptions
import zoned.framework.libs.dataURLtoBlob
import zoned.framework.libs.saveAs
import zoned.framework.libs.toPng

/**
 * Rasterizes a DOM element to a PNG download via the html-to-image binding (provided by zoned-js).
 *
 * Cross-origin `<img>` elements taint html-to-image's offscreen canvas and abort the capture, so
 * they are stripped first (OSM map tiles are kept — they serve CORS headers). [onComplete] runs
 * after the capture finishes or fails; the caller uses it to re-render and restore stripped images.
 */
class PngExporter {

    fun export(elementId: String, fileName: String, onComplete: () -> Unit) {
        val element = document.getElementById(elementId) as? HTMLElement
        if (element == null) {
            onComplete()
            return
        }

        element.querySelectorAll("img").asList()
            .filterIsInstance<HTMLImageElement>()
            .filter { !it.hasAttribute("crossorigin") && !it.src.contains("tile.osm") }
            .forEach { it.remove() }

        val options = object : HtmlToPngOptions {
            override val width = element.scrollWidth
            override val height = element.scrollHeight
        }

        toPng(element.unsafeCast<WebElement>(), options).then { dataUrl ->
            saveAs(dataURLtoBlob(dataUrl), fileName)
            onComplete()
        }.catch { error ->
            console.error("PNG export failed: $error")
            onComplete()
        }
    }
}
