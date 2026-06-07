package tdz.controller.io

import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import org.w3c.files.FileReader
import org.w3c.files.get

/**
 * Opens a transient file dialog and hands back the chosen file's text. This is the only impure,
 * DOM-bound piece of the upload path — kept deliberately tiny so the parsing ([TdzDocument]) and
 * placement ([CanvasImporter]) logic can be tested without a browser file dialog.
 */
object FilePicker {

    fun pick(accept: String, onText: (name: String, content: String) -> Unit) {
        val input = (document.createElement("input") as HTMLInputElement).apply {
            type = "file"
            this.accept = accept
            style.display = "none"
        }

        input.addEventListener("change", {
            val file = input.files?.get(0)
            if (file == null) {
                input.remove()
            } else {
                val reader = FileReader()
                reader.onload = {
                    onText(file.name, reader.result as String)
                    input.remove()
                }
                reader.onerror = { input.remove() }
                reader.readAsText(file)
            }
        })

        // Some browsers only fire 'change' for an in-document input; removed again once read.
        document.body?.appendChild(input)
        input.click()
    }
}
