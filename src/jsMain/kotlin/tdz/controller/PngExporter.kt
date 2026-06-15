package tdz.controller

import web.dom.ElementId
import web.dom.document
import web.html.HTMLElement
import web.html.HTMLImageElement
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
        val element = document.getElementById(ElementId(elementId))
        if (element == null) {
            onComplete()
            return
        }

        val images = element.querySelectorAll("img")
        for (i in 0 until images.length) {
            val img = images[i] as? HTMLImageElement ?: continue
            if (!img.hasAttribute("crossorigin") && !img.src.contains("tile.osm")) img.remove()
        }

        val options = object : HtmlToPngOptions {
            override val width = element.scrollWidth
            override val height = element.scrollHeight
        }

        toPng(element, options).then { dataUrl ->
            saveAs(dataURLtoBlob(dataUrl), fileName)
            onComplete()
        }.catch { error ->
            console.error("PNG export failed: $error")
            onComplete()
        }
    }
}
