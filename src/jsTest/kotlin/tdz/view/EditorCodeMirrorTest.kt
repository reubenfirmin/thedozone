package tdz.view

import kotlinx.html.div
import tdz.Index
import tdz.controller.events.ColumnEdited
import tdz.controller.events.Event
import tdz.controller.events.ExitEditor
import tdz.controller.events.NavigateColumn
import tdz.model.CanvasName
import tdz.model.Direction
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.pollUntil
import tdz.rootCanvas
import web.dom.document
import web.events.EventType
import web.html.HTMLElement
import web.keyboard.KeyboardEvent
import web.keyboard.KeyboardEventInit
import zoned.framework.dom.Ref
import zoned.framework.dom.ref
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import zoned.framework.libs.CodeMirrorWrapper
import zoned.framework.libs.EditorPosition
import zoned.framework.libs.loadCodeMirror
import kotlin.js.Promise
import kotlin.test.*

class EditorCodeMirrorTest {
    private val canvas = canvasFromTemplate(CanvasName("_cm-test"), null, SINGLE_PANE)
    private val bus = EventBus<Event>()
    private val host = Ref<HTMLElement>()
    private val column = EditorColumn(canvas, canvas.firstNonLayout(), bus) {
        listOf(CanvasName("Work/My Other Board"), CanvasName("Home"), canvas.name)
    }

    private fun mount() {
        addToBody { div { ref(host); with(column) { render(emptyList()) } } }
    }

    private fun ready(): Promise<CodeMirrorWrapper> {
        mount()
        return pollUntil("CodeMirror initialization") { column.editor != null }.then { column.editor!! }
    }

    @AfterTest
    fun cleanup() { column.destroy(); host.getOrNull()?.remove() }

    @Test
    fun enterContinuesListsAndExitsEmptyItemsWithoutAddingCardBreaks(): Promise<Unit> = ready().then { ed ->
        ed.setValue("  9. task")
        ed.pressKey("Enter")
        assertEquals("  9. task\n  10. ", ed.getValue())
        ed.pressKey("Enter")
        assertEquals("  9. task\n", ed.getValue())
        ed.setValue("* first\n* second")
        ed.select(EditorPosition(0, 0), EditorPosition(1, 8))
        ed.pressKey("Enter")
        assertEquals("\n", ed.getValue(), "Enter replaces the selection")
    }

    @Test
    fun selectedLinesIndentAndOutdentTogetherWithUndo(): Promise<Unit> = ready().then { ed ->
        ed.setValue("* first\nparagraph\n* last")
        ed.select(EditorPosition(0, 0), EditorPosition(2, 6))
        ed.pressKey("Tab")
        assertEquals("  * first\n  paragraph\n  * last", ed.getValue())
        ed.pressKey("Tab", shift = true)
        assertEquals("* first\nparagraph\n* last", ed.getValue())
        ed.setValue("* parent")
        ed.pressKey("Tab")
        assertEquals("  * parent", ed.getValue())
        assertTrue(ed.undo())
        assertEquals("* parent", ed.getValue())
        assertEquals(EditorPosition(0, 8), ed.getCursorPosition())
        assertTrue(ed.redo())
        assertEquals("  * parent", ed.getValue())
    }

    @Test
    fun liveCompletionMatchesNamesWithSpacesAndKeepsOneClosingBracket(): Promise<Unit> = ready().then { ed ->
        ed.type("[")
        assertEquals("[]", ed.getValue())
        ed.type("My Other")
        pollUntil("multiword completion") { ed.completionLabels() == listOf("My Other Board") }
    }.then {
        // CodeMirror briefly ignores acceptance after opening a menu, to avoid accidental picks.
        pollUntil("Tab to accept completion") {
            val ed = column.editor!!
            ed.pressKey("Tab")
            ed.getValue() == "[/Work/My-Other-Board (My Other Board)]"
        }
    }.then {
        val ed = column.editor!!
        assertEquals("[/Work/My-Other-Board (My Other Board)]", ed.getValue())
        var saved = ""
        bus.on<ColumnEdited> { saved = it.markdown }
        column.save()
        assertEquals(ed.getValue(), saved)
    }

    @Test
    fun editorNavigationAndEscapeRespectCompletionPopup(): Promise<Unit> = ready().then { ed ->
        var direction: Direction? = null
        bus.on<NavigateColumn> { direction = it.direction }
        ed.setValue("# One\ntext\n# Two\ntext")
        ed.pressKey("ArrowUp", ctrl = true)
        assertEquals(EditorPosition(2, 0), ed.getCursorPosition())
        ed.pressKey("ArrowUp", ctrl = true)
        assertEquals(EditorPosition(0, 0), ed.getCursorPosition())
        ed.pressKey("ArrowDown", ctrl = true)
        assertEquals(EditorPosition(2, 0), ed.getCursorPosition())
        ed.pressKey("ArrowRight", alt = true)
        assertEquals(Direction.RIGHT, direction)
        assertTrue(ed.pressKey("ArrowLeft").defaultPrevented, "global shortcuts must ignore editor navigation")
        ed.setValue("")
        ed.type("[")
        pollUntil("completion popup") { ed.completionLabels().isNotEmpty() }
    }.then {
        val ed = column.editor!!
        var exits = 0
        bus.on<ExitEditor> { exits++ }
        ed.pressKey("Escape")
        assertEquals(0, exits, "first Escape only closes completion")
        assertFalse(ed.completionActive())
        ed.pressKey("Escape")
        assertEquals(1, exits)
    }

    @Test
    fun pageKeysAtDocumentBoundariesDoNotRunBoardShortcuts(): Promise<Unit> = ready().then { ed ->
        val index = Index(Model(rootCanvas), EventBus<Event>())
        val boardActions = mutableListOf<String>()
        val actions = object : ShortcutActions by index {
            override fun goToParentBoard(): Boolean { boardActions.add("parent"); return true }
            override fun openBoardPalette(): Boolean { boardActions.add("palette"); return true }
        }
        KeyboardShortcuts(actions).install()
        try {
            ed.setValue("hello")
            ed.focus()
            assertTrue(index.isEditableFocused())
            for (shift in listOf(false, true)) {
                ed.moveCursorTo(0, 0)
                ed.pressKey("PageUp", shift = shift)
                ed.moveCursorTo(0, 5)
                ed.pressKey("PageDown", shift = shift)
            }
            assertEquals(emptyList(), boardActions, "paging in the editor must not navigate boards")

            ed.view.contentDOM.blur()
            assertFalse(index.isEditableFocused())
            for (key in listOf("PageUp", "PageDown")) {
                val event = KeyboardEvent(EventType("keydown"), KeyboardEventInit(key = key, cancelable = true))
                document.dispatchEvent(event)
                assertTrue(event.defaultPrevented, "board shortcuts still work outside editable fields")
            }
            assertEquals(listOf("parent", "palette"), boardActions)
        } finally {
            KeyboardShortcuts(index).install()
        }
    }

    @Test
    fun detachBeforeAsyncInitializationDoesNotCreateAnOrphanEditor(): Promise<Unit> {
        mount()
        column.detach()
        return loadCodeMirror().then {
            assertNull(column.editor)
        }
    }
}
