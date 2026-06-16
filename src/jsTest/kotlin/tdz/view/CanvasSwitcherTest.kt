package tdz.view

import kotlinx.browser.window
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasSwitcherTest {

    @AfterTest fun clean() { window.localStorage.clear() }

    // ---- childBoardNames (parent-based listing) ---------------------------------

    @Test
    fun listsDirectChildrenOfCurrentBoard() {
        val model = Model(CanvasName("cur"))
        model.addCanvas(canvasFromTemplate(CanvasName("cur"), null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("cur/a"), CanvasName("cur"), SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("cur/b"), CanvasName("cur"), SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("other/z"), CanvasName("other"), SINGLE_PANE))

        val children = childBoardNames(model, CanvasName("cur"))
        assertEquals(listOf("a", "b"), children.map { it.leaf() }, "only direct children, sorted by leaf")
    }

    @Test
    fun dotDotCardOnBoardDoesNotAppearAsChild() {
        // ensurePath inserts a [..] parent-link card; the switcher must not list ".." as a child
        val model = Model(CanvasName("p"))
        model.addCanvas(canvasFromTemplate(CanvasName("p"), null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("p/child"), CanvasName("p"), SINGLE_PANE))
        // Simulate the [..] parent-link card that ensurePath inserts
        val pCanvas = model.getCanvas(CanvasName("p"))!!
        model.updateColumn(CanvasName("p"), pCanvas.firstNonLayout(), "[..]\n")

        val children = childBoardNames(model, CanvasName("p"))
        val labels = children.map { it.leaf() }
        assertFalse(labels.contains(".."), "'..' link card must NOT appear as a child board")
        assertTrue(labels.contains("child"), "real child board still listed")
    }

    @Test
    fun unrelatedBoardsDoNotAppearAsChildren() {
        val model = Model(CanvasName("home"))
        model.addCanvas(canvasFromTemplate(CanvasName("home"), null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("other"), null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("other/child"), CanvasName("other"), SINGLE_PANE))

        val children = childBoardNames(model, CanvasName("home"))
        assertTrue(children.isEmpty(), "boards parented to a different board must not appear")
    }

    @Test
    fun childrenSortedAlphabeticallyByLeaf() {
        val model = Model(CanvasName("root"))
        model.addCanvas(canvasFromTemplate(CanvasName("root"), null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("root/zebra"), CanvasName("root"), SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("root/apple"), CanvasName("root"), SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("root/mango"), CanvasName("root"), SINGLE_PANE))

        val children = childBoardNames(model, CanvasName("root"))
        assertEquals(listOf("apple", "mango", "zebra"), children.map { it.leaf() })
    }

    // ---- childBoardSections (menu builder) --------------------------------------

    @Test
    fun buildsSectionsFromChildNames() {
        val model = Model(CanvasName("base"))
        model.addCanvas(canvasFromTemplate(CanvasName("base"), null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("base/alpha"), CanvasName("base"), SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("base/beta"), CanvasName("base"), SINGLE_PANE))

        val picked = mutableListOf<CanvasName>()
        val children = childBoardNames(model, CanvasName("base"))
        val sections = childBoardSections(children) { picked += it }
        val items = sections.flatMap { it.items }

        assertEquals(listOf("1. alpha", "2. beta"), items.map { it.label }, "items are numbered with leaf labels")
        assertTrue(items.all { it.enabled }, "board entries are selectable")
        items[1].onSelect()
        assertEquals(listOf(CanvasName("base/beta")), picked, "selecting fires with the full absolute path")
    }

    @Test
    fun emptyChildrenProducesNoSections() {
        assertTrue(childBoardSections(emptyList()) { }.isEmpty(), "no child boards => no menu sections")
    }

    // ---- switcherIndexForKey (unchanged) ----------------------------------------

    @Test
    fun numberKeysMapToBoardIndexesWithinRange() {
        assertEquals(0, switcherIndexForKey("1", 3))
        assertEquals(2, switcherIndexForKey("3", 3))
        assertEquals(null, switcherIndexForKey("4", 3), "out of range => no selection")
        assertEquals(null, switcherIndexForKey("0", 3), "0 has no board")
        assertEquals(null, switcherIndexForKey("a", 3), "non-digit => no selection")
    }
}
