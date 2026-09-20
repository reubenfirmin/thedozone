package tdz.view

import kotlinx.html.div
import tdz.controller.events.ColumnEdited
import tdz.controller.events.Event
import tdz.model.Card.Companion.taskItems
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.markdown.MarkdownService
import tdz.pollUntil
import web.dom.document
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import web.clipboard.ClipboardEvent
import web.clipboard.ClipboardEventInit
import web.data.DataTransfer
import web.events.EventType
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorListIndentTest {

    private var editor: EditorColumn? = null

    @AfterTest
    fun cleanup() {
        editor?.destroy()
        document.body.replaceChildren()
    }

    @Test
    fun pasteParagraphThenIndentAndOutdentTheNextListItem(): Promise<Unit> {
        val canvas = canvasFromTemplate(CanvasName("_list-indent"), null, SINGLE_PANE)
        val column = canvas.firstNonLayout()
        val bus = EventBus<Event>()
        var saved = ""
        bus.on<ColumnEdited> { saved = it.markdown }
        val editor = EditorColumn(canvas, column, bus).also { this.editor = it }
        addToBody { div { with(editor) { render(emptyList()) } } }

        return pollUntil("CodeMirror to initialize with its completer") {
            editor.editor != null
        }.then {
            val ed = editor.editor!!
            ed.setValue("* Before\n* \n* After")
            ed.moveCursorTo(1, 2)
            val clipboard = DataTransfer()
            clipboard.setData("text/plain", "First pasted line\nSecond pasted line\n")
            ed.view.contentDOM.dispatchEvent(ClipboardEvent(EventType("paste"), ClipboardEventInit(
                clipboardData = clipboard, bubbles = true, cancelable = true,
            )))
            ed.type("* Child")

            // Run Tab through the keyboard bindings, including the custom list handler.
            ed.pressKey("Tab")
            val indented = "* Before\n* First pasted line\nSecond pasted line\n  * Child\n* After"
            assertEquals(indented, ed.getValue())
            editor.save()
            val cards = MarkdownService.fromMd(saved, column)
            assertEquals(listOf(0, 0, 1, 0), cards.flatMap { it.taskItems() }.map { it.level })
            assertEquals("$indented\n", MarkdownService.toMd(cards, false))
            addToBody { div { with(CardRenderer) { render(canvas.name, cards, bus) } } }
            assertEquals(4, document.querySelectorAll(".${CssClasses.TASK}").length)

            // Shift-Tab must restore the original level and keep the pasted paragraph intact.
            ed.pressKey("Tab", shift = true)
            editor.save()
            val outdented = indented.replace("  * Child", "* Child")
            assertEquals(outdented, saved)
            assertEquals("$outdented\n",
                MarkdownService.toMd(MarkdownService.fromMd(saved, column), false))
        }
    }
}
