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
 * Real-browser (karma) check that the board-link completer actually wires up: entering editor mode
 * must load the `language_tools` extension and apply the autocompletion options + completer without
 * throwing. If the extension import or option-set silently failed, [EditorColumn.completerInstalled]
 * would never flip and this times out — turning that "does nothing" symptom into a hard failure.
 */
class EditorBoardLinkInstallTest {

    private val canvas = CanvasName("_bl-test")
    private var column = ColumnId("1")   // reassigned to the board's opaque leaf after creation

    @AfterTest
    fun cleanup() { document.body.asDynamic()?.innerHTML = "" }

    @Test
    fun enteringEditorModeInstallsTheBoardLinkCompleter(): Promise<Unit> {
        val bus = EventBus<Event>()
        val model = Model(canvas)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        column = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, column, "# Notes\n")
        // Two OTHER boards the completer should be able to suggest from.
        model.addCanvas(canvasFromTemplate(CanvasName("Work"), null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("Home"), null, SINGLE_PANE))
        val spec = model.getCanvas(canvas) ?: error("setup")
        val columns = CanvasColumns(spec, bus, model) { id -> model.cards(canvas, id) }
        addToBody {
            div {
                id = "bl-host"
                with(columns) { render(spec.columns.filter { !spec.layoutMap.containsKey(it) }) }
            }
        }

        columns.toggleMode(column)   // CARD -> EDITOR (Ace + language_tools are code-split, so async)
        return pollUntil("the board-link completer to install") {
            columns.editorCache[column]?.completerInstalled() == true
        }.then {
            val editor = columns.editorCache[column] ?: error("editor")
            assertTrue(editor.completerInstalled(), "language_tools loaded and the completer was applied")
            // Force the popup with the caret inside an open "[" and confirm Ace invokes our completer
            // and it offers the other boards (the real end-to-end path, not just install).
            editor.triggerCompletionForTest("[")
            pollUntil("Ace to invoke the board-link completer") { editor.lastCompletionCount >= 0 }
        }.then {
            val editor = columns.editorCache[column] ?: error("editor")
            assertTrue(editor.lastCompletionCount >= 2,
                "completer offered the other boards (got ${editor.lastCompletionCount})")
            columns.saveEditors()
        }
    }
}
