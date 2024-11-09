package tdz.controller

import kotlinx.browser.localStorage
import org.w3c.dom.get
import tdz.model.*
import org.w3c.files.Blob
import tdz.model.localstorage.CanvasDoc
import tdz.model.markdown.MarkdownService
import zoned.framework.Debugger.debug
import zoned.framework.libs.BrowserFsAccess
import zoned.framework.libs.BrowserFsAccess.directoryOpen
import zoned.framework.libs.FileHandleOptions
import zoned.framework.libs.saveAs

class Downloader(val model: Model) {

    fun download(canvas: Canvas) {
        val canvasName = canvas.name
        val cards = model.allCards(canvasName).flatten()
        val markdown = MarkdownService.toMd(cards)
        val layout = JSON.stringify(CanvasDoc(canvasName, canvas.parent, canvas.columns, canvas.layouts).toJson())
        val output = layout + "\n" + markdown
        val blob = Blob(arrayOf(output))
        saveAs(blob, "${canvasName.normalized()}.tdz")

        // the UX around browser-fs is not great
        if (localStorage["experimental_fs"] != null) {
            val access = directoryOpen(object : BrowserFsAccess.BrowserFsOptions {
                override var extensions = arrayOf(".tdz")
                override var recursive = true
                override var startIn = "downloads"
                override var mode = "readwrite"
            }).then {
                debug("${it.size}")
                it.forEach { dir ->
                    dir.fileHandle.getFile().then { file ->
                        debug("file.name")
                    }
                    dir.directoryHandle.getFileHandle("$canvasName.tdz", object : FileHandleOptions {
                        override var create = true
                    }).then { file ->
                        file.createWritable().then { writable ->
                            writable.write(blob).then {
                                debug("Wrote file!")
                            }
                        }
                    }
                }
            }
        }
    }
} 