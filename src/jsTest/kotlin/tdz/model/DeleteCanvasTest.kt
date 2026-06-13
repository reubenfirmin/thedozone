package tdz.model

import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplate.TWO_COLUMNS_VERTICAL
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.localstorage.CanvasStorageKeys
import tdz.model.localstorage.flushPendingWrites
import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Board-level operations behind the "All boards" view: enumerate every board, detect empties, and a
 * fully-local delete (manifest + columns + modes) that touches no remote/sync state — sync
 * propagates it independently.
 */
class DeleteCanvasTest {

    @AfterTest fun clean() { window.localStorage.clear() }

    @Test
    fun allCanvasNamesListsEveryStoredBoard() {
        val model = Model(CanvasName("_acn"))
        model.addCanvas(canvasFromTemplate(CanvasName("_acn"), null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("alpha"), null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(CanvasName("beta"), null, SINGLE_PANE))
        val names = model.allCanvasNames().map { it.normalized() }
        assertTrue(names.containsAll(listOf("_acn", "alpha", "beta")), "all boards enumerated: $names")
    }

    @Test
    fun isCanvasEmptyReflectsContent() {
        val model = Model(CanvasName("_empty"))
        val full = CanvasName("full"); val blank = CanvasName("blank")
        model.addCanvas(canvasFromTemplate(full, null, SINGLE_PANE))
        model.addCanvas(canvasFromTemplate(blank, null, SINGLE_PANE))
        model.updateColumn(full, model.getCanvas(full)!!.firstNonLayout(), "# has content\n* a\n")
        assertFalse(model.isCanvasEmpty(full), "a board with cards is not empty")
        assertTrue(model.isCanvasEmpty(blank), "a board with no card content is empty")
    }

    @Test
    fun deleteCanvasRemovesManifestColumnsAndModesLocally() {
        val model = Model(CanvasName("_del"))
        val name = CanvasName("doomed")
        model.addCanvas(canvasFromTemplate(name, null, TWO_COLUMNS_VERTICAL))
        val cols = model.getCanvas(name)!!.columns
        val content = cols.first { !model.getCanvas(name)!!.layoutMap.containsKey(it) }
        model.updateColumn(name, content, "# stuff\n* x\n")
        model.setColumnMode(name, content, Mode.CARD)
        flushPendingWrites()   // write-behind: force buffered manifest/column writes to localStorage
        assertNotNull(window.localStorage.getItem(CanvasStorageKeys.manifest(name)))
        assertNotNull(window.localStorage.getItem(CanvasStorageKeys.column(name, content)))
        assertNotNull(window.localStorage.getItem(CanvasStorageKeys.columnMode(name, content)))

        model.deleteCanvas(name)

        assertNull(window.localStorage.getItem(CanvasStorageKeys.manifest(name)), "manifest gone")
        cols.forEach { assertNull(window.localStorage.getItem(CanvasStorageKeys.column(name, it)), "column $it gone") }
        assertNull(window.localStorage.getItem(CanvasStorageKeys.columnMode(name, content)), "mode flag gone")
        assertEquals(null, model.getCanvas(name), "board no longer exists")
        assertFalse(model.allCanvasNames().any { it.normalized() == name.normalized() }, "not in the board list")
    }
}
