package tdz.controller.io

import web.dom.document
import web.events.EventType
import web.events.addEventListener
import web.file.FileReader
import web.html.HTMLInputElement
import web.html.InputType
import web.html.file

/**
 * Opens a transient file dialog and hands back the chosen file's text. This is the only impure,
 * DOM-bound piece of the upload path — kept deliberately tiny so the parsing ([TdzDocument]) and
 * placement ([CanvasImporter]) logic can be tested without a browser file dialog.
 */
object FilePicker {

    fun pick(accept: String, onText: (name: String, content: String) -> Unit) {
        val input = (document.createElement("input") as HTMLInputElement).apply {
            type = InputType.file
            this.accept = accept
            style.display = "none"
        }

        input.addEventListener(EventType("change"), {
            val file = input.files?.item(0)
            if (file == null) {
                input.remove()
            } else {
                val reader = FileReader()
                reader.addEventListener(EventType("load"), {
                    onText(file.name, reader.result as String)
                    input.remove()
                })
                reader.addEventListener(EventType("error"), { input.remove() })
                reader.readAsText(file)
            }
        })

        // Some browsers only fire 'change' for an in-document input; removed again once read.
        document.body.appendChild(input)
        input.click()
    }
}
