package tdz.view

import tdz.controller.events.Event
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.pollUntil
import web.dom.document
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import kotlinx.html.div
import kotlinx.html.id
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reproduces the actual user gesture: typing `[` in the editor must open the board-link menu. This
 * drives CodeMirror's live autocompletion, unlike a manual Ctrl+Space trigger — so it
 * is the test that catches "I typed `[` and nothing happened".
 */
class EditorBoardLinkLiveTest {

    private val canvas = CanvasName("_bll-test")
    private var column = ColumnId("1")   // reassigned to the board's opaque leaf after creation
    private var columns: CanvasColumns? = null

    @AfterTest
    fun cleanup() { columns?.saveEditors(); document.body.replaceChildren() }

    @Test
    fun typingOpenBracketOpensBoardCompletions(): Promise<Unit> {
        val bus = EventBus<Event>()
        val model = Model(canvas)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        column = model.getCanvas(canvas)!!.firstNonLayout()
        model.addCanvas(canvasFromTemplate(CanvasName("Work"), null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("Home"), null, SINGLE_PANE))
        val spec = model.getCanvas(canvas) ?: error("setup")
        val columns = CanvasColumns(spec, bus, model) { id -> model.cards(canvas, id) }
            .also { this.columns = it }
        addToBody {
            div {
                id = "bll-host"
                with(columns) { render(spec.columns.filter { !spec.layoutMap.containsKey(it) }) }
            }
        }

        columns.toggleMode(column)
        return pollUntil("the completer to install") {
            columns.editorCache[column]?.editor != null
        }.then {
            val editor = columns.editorCache[column]!!.editor!!
            editor.type("[")
            pollUntil("typing '[' to open the board menu") {
                editor.completionLabels().containsAll(listOf("Work", "Home"))
            }
        }.then {
            assertTrue(columns.editorCache[column]!!.editor!!.completionActive())
        }
    }
}
