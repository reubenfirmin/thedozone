package tdz.controller

import tdz.controller.io.TdzDocument
import tdz.model.*
import web.blob.Blob
import web.blob.BlobPropertyBag
import zoned.framework.libs.saveAs

class Downloader(val model: Model) {

    fun download(canvas: Canvas) {
        val canvasName = canvas.name
        val cards = model.allCards(canvasName).flatten()
        val blob = Blob(arrayOf(TdzDocument.encode(canvas, cards)), BlobPropertyBag(type = "application/octet-stream"))
        // Filename must be the leaf segment only -- a path with "/" (nested boards) or an empty
        // root path (".tdz") breaks the browser's download naming. Root shows as "Home" in the UI.
        val fileName = canvasName.segments().lastOrNull() ?: "Home"
        saveAs(blob, "$fileName.tdz")
    }
}
