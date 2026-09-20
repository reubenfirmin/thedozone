package tdz.view

import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.Event
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import web.dom.document
import tdz.pollUntil
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import zoned.framework.interop.getHtmlElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Toggling a column to CARD and back reuses the editor instance and
 * its live DOM are parked on detach and re-adopted on the next EDITOR render, with the buffer
 * refreshed from the model (card-mode edits in between must show up). Real teardown happens only
 * when the canvas render is disposed.
 */
class EditorReuseTest {

    private val canvas = CanvasName("_er-test")
    private var column = ColumnId("1")   // reassigned to the board's opaque leaf after creation
    private var columns: CanvasColumns? = null

    @AfterTest
    fun cleanup() {
        columns?.saveEditors()
        document.body.replaceChildren()
    }

    @Test
    fun modeToggleReusesTheEditorInstanceAndRefreshesContent(): Promise<Unit> {
        val bus = EventBus<Event>()
        val model = Model(canvas)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        column = model.getCanvas(canvas)!!.firstNonLayout()
        model.updateColumn(canvas, column, "# Original\n")
        val spec = model.getCanvas(canvas) ?: error("setup")
        val columns = CanvasColumns(spec, bus, model) { id -> model.cards(canvas, id) }
            .also { this.columns = it }
        addToBody {
            div {
                id = "er-host"
                with(columns) { render(spec.columns.filter { !spec.layoutMap.containsKey(it) }) }
            }
        }

        columns.toggleMode(column)   // CARD -> EDITOR (first init — async: CodeMirror is code-split)
        // Await the chunk load + init, then everything after is synchronous re-adoption.
        return pollUntil("CodeMirror to load and initialize") {
            columns.editorCache[column]?.editor != null
        }.then {
            val editor = columns.editorCache[column]
            assertNotNull(editor, "the editor is cached for reuse")
            val wrapper = assertNotNull(editor.editor)
            assertTrue("Original" in wrapper.getValue(), "editor loaded the column text")
            val editorEl = getHtmlElement(DomIds.editor(column))
            assertNotNull(editorEl)

            columns.toggleMode(column)   // EDITOR -> CARD (parks the editor)
            assertTrue(getHtmlElement(DomIds.editor(column)) == null, "card mode shows no editor element")

            model.updateColumn(canvas, column, "# Changed elsewhere\n")
            columns.toggleMode(column)   // CARD -> EDITOR again (re-adopt, not re-init)

            assertSame(editor, columns.editorCache[column], "same editor object across toggles")
            assertSame(wrapper, editor.editor, "the second toggle reuses the CodeMirror instance")
            assertSame(editorEl, getHtmlElement(DomIds.editor(column)), "the live editor DOM was re-adopted")
            assertTrue("Changed elsewhere" in wrapper.getValue(),
                "the re-adopted editor shows the model's current content")

            columns.saveEditors()   // canvas dispose → real teardown
            assertTrue(columns.editorCache.isEmpty(), "dispose destroys and drops cached editors")
        }
    }
}
