package tdz.controller

import tdz.controller.io.TdzDocument
import tdz.model.*
import web.blob.Blob
import zoned.framework.libs.saveAs

class Downloader(val model: Model) {

    fun download(canvas: Canvas) {
        val canvasName = canvas.name
        val cards = model.allCards(canvasName).flatten()
        val blob = Blob(arrayOf(TdzDocument.encode(canvas, cards)))
        saveAs(blob, "${canvasName.normalized()}.tdz")
    }
}
