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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Ace initialization is the heaviest single interaction in the app (~100ms: DOM build, tokenizer,
 * mode load). Toggling a column to CARD and back must NOT pay it again: the editor instance and
 * its live DOM are parked on detach and re-adopted on the next EDITOR render, with the buffer
 * refreshed from the model (card-mode edits in between must show up). Real teardown happens only
 * when the canvas render is disposed.
 */
class EditorReuseTest {

    private val canvas = CanvasName("_er-test")
    private val column = ColumnId(1)

    @AfterTest
    fun cleanup() {
        document.body.asDynamic()?.innerHTML = ""
    }

    @Test
    fun modeToggleReusesTheAceInstanceAndRefreshesContent(): Promise<Unit> {
        val bus = EventBus<Event>()
        val model = Model(canvas)
        model.addCanvas(canvasFromTemplate(canvas, null, SINGLE_PANE))
        model.updateColumn(canvas, column, "# Original\n")
        val spec = model.getCanvas(canvas) ?: error("setup")
        val columns = CanvasColumns(spec, bus, model) { id -> model.cards(canvas, id) }
        addToBody {
            div {
                id = "er-host"
                with(columns) { render(spec.columns.filter { !spec.layoutMap.containsKey(it) }) }
            }
        }

        columns.toggleMode(column)   // CARD -> EDITOR (first init — async: Ace is code-split)
        // Await the chunk load + init, then everything after is synchronous re-adoption.
        return pollUntil("Ace to load and initialize") {
            columns.editorCache[column]?.initCount == 1
        }.then {
            val editor = columns.editorCache[column]
            assertNotNull(editor, "the editor is cached for reuse")
            assertTrue("Original" in editor.text(), "editor loaded the column text")
            val editorEl = getHtmlElement(DomIds.editor(column))
            assertNotNull(editorEl)

            columns.toggleMode(column)   // EDITOR -> CARD (parks the editor)
            assertTrue(getHtmlElement(DomIds.editor(column)) == null, "card mode shows no editor element")

            model.updateColumn(canvas, column, "# Changed elsewhere\n")
            columns.toggleMode(column)   // CARD -> EDITOR again (re-adopt, not re-init)

            assertSame(editor, columns.editorCache[column], "same editor object across toggles")
            assertEquals(1, editor.initCount, "the second toggle must NOT re-initialize Ace")
            assertSame(editorEl, getHtmlElement(DomIds.editor(column)), "the live Ace DOM was re-adopted")
            assertTrue("Changed elsewhere" in editor.text(),
                "the re-adopted editor shows the model's current content (got '${editor.text().take(40)}')")

            columns.saveEditors()   // canvas dispose → real teardown
            assertTrue(columns.editorCache.isEmpty(), "dispose destroys and drops cached editors")
        }
    }
}
