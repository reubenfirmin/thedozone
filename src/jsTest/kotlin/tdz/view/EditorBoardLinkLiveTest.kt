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
 * drives Ace's LIVE autocompletion (the afterExec path), unlike a manual Ctrl+Space trigger — so it
 * is the test that catches "I typed `[` and nothing happened".
 */
class EditorBoardLinkLiveTest {

    private val canvas = CanvasName("_bll-test")
    private val column = ColumnId(1)

    @AfterTest
    fun cleanup() { document.body.asDynamic()?.innerHTML = "" }

    @Test
    fun typingOpenBracketOpensBoardCompletions(): Promise<Unit> {
        val bus = EventBus<Event>()
        val model = Model(canvas)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("Work"), null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("Home"), null, SINGLE_PANE))
        val spec = model.getCanvas(canvas) ?: error("setup")
        val columns = CanvasColumns(spec, bus, model) { id -> model.cards(canvas, id) }
        addToBody {
            div {
                id = "bll-host"
                with(columns) { render(spec.columns.filter { !spec.layoutMap.containsKey(it) }) }
            }
        }

        columns.toggleMode(column)
        return pollUntil("the completer to install") {
            columns.editorCache[column]?.completerInstalled() == true
        }.then {
            columns.editorCache[column]?.typeForTest("[")   // the real gesture
            pollUntil("typing '[' to open the board menu") {
                (columns.editorCache[column]?.lastCompletionCount ?: -1) >= 0
            }
        }.then {
            val editor = columns.editorCache[column] ?: error("editor")
            assertTrue(editor.lastCompletionCount >= 2,
                "typing '[' should offer the other boards (got ${editor.lastCompletionCount})")
            columns.saveEditors()
        }
    }
}
