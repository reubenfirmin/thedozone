package tdz.controller.io

import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Split
import tdz.model.markdown.MarkdownService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanvasImporterTest {

    /** In-memory CanvasStore — no localStorage, mirrors the codebase's fake-storage test style. */
    private class FakeCanvasStore : CanvasStore {
        val canvases = mutableMapOf<String, Canvas>()
        val columns = mutableMapOf<String, List<Card>>()
        private fun colKey(n: CanvasName, c: ColumnId) = "${n.normalized()}|$c"
        override fun exists(canvasName: CanvasName) = canvases.containsKey(canvasName.normalized())
        override fun getCanvas(canvasName: CanvasName) = canvases[canvasName.normalized()]
        override fun addCanvas(canvas: Canvas) { canvases[canvas.name.normalized()] = canvas }
        override fun cards(canvasName: CanvasName, columnId: ColumnId) = columns[colKey(canvasName, columnId)] ?: emptyList()
        override fun saveCards(canvasName: CanvasName, column: ColumnId, cards: List<Card>) {
            columns[colKey(canvasName, column)] = cards
        }
    }

    // current canvas: column 0 is the root layout, column 1 is the only leaf
    private val current = Canvas(
        CanvasName("home"), null, listOf(ColumnId(0), ColumnId(1)),
        listOf(ColumnLayoutSpec(ColumnId(0), Split.VERTICAL, listOf(ColumnId(1)))),
    )

    private fun storeWithCurrent(): FakeCanvasStore =
        FakeCanvasStore().apply { addCanvas(current) }

    private fun cardsIn(column: ColumnId, md: String) = MarkdownService.fromMd(md, column)

    @Test
    fun newCanvasFromPrototypeUsesPrototypeStructureAndCurrentAsParent() {
        val store = storeWithCurrent()
        val prototype = Canvas(
            CanvasName("imported"), null, listOf(ColumnId(0), ColumnId(1), ColumnId(2)),
            listOf(ColumnLayoutSpec(ColumnId(0), Split.VERTICAL, listOf(ColumnId(1), ColumnId(2)))),
        )
        val doc = TdzDocument(prototype, cardsIn(ColumnId(1), "# Hello\n"))

        CanvasImporter(store).import("imported", doc, ImportMode.NEW_CANVAS, current)

        val created = assertNotNull(store.getCanvas(CanvasName("imported")))
        assertEquals(listOf(0, 1, 2).map(::ColumnId), created.columns)
        assertEquals(CanvasName("home"), created.parent, "new canvas is parented under the current canvas")
    }

    @Test
    fun newCanvasSavesCardsAndAddsLinkOnCurrent() {
        val store = storeWithCurrent()
        val prototype = Canvas(
            CanvasName("imported"), null, listOf(ColumnId(0), ColumnId(1)),
            listOf(ColumnLayoutSpec(ColumnId(0), Split.VERTICAL, listOf(ColumnId(1)))),
        )
        val doc = TdzDocument(prototype, cardsIn(ColumnId(1), "# Hello\n"))

        CanvasImporter(store).import("imported", doc, ImportMode.NEW_CANVAS, current)

        assertTrue(store.cards(CanvasName("imported"), ColumnId(1)).isNotEmpty(), "cards land in the new canvas")
        val link = store.cards(CanvasName("home"), ColumnId(1)).firstOrNull { it.isCanvasLink() }
        assertNotNull(link, "a link card to the new canvas is added on the current canvas")
        assertEquals(CanvasName("imported"), link.linkedCanvas())
    }

    @Test
    fun newCanvasWithoutPrototypeAutoGeneratesLayout() {
        val store = storeWithCurrent()
        val doc = TdzDocument(null, cardsIn(ColumnId(1), "# A\n") + cardsIn(ColumnId(2), "# B\n"))

        CanvasImporter(store).import("loose", doc, ImportMode.NEW_CANVAS, current)

        val created = assertNotNull(store.getCanvas(CanvasName("loose")))
        // two card columns -> columns [0,1,2], column 0 the root layout over [1,2]
        assertEquals(listOf(0, 1, 2).map(::ColumnId), created.columns)
        assertNotNull(created.layoutMap[ColumnId(0)], "column 0 is the generated root layout")
    }

    @Test
    fun duplicateNameIsSanitizedToUniqueName() {
        val store = storeWithCurrent()
        store.addCanvas(Canvas(CanvasName("imported"), null, listOf(ColumnId(0)), listOf(ColumnLayoutSpec(ColumnId(0), Split.VERTICAL, emptyList()))))
        val doc = TdzDocument(null, cardsIn(ColumnId(1), "# Hello\n"))

        CanvasImporter(store).import("imported", doc, ImportMode.NEW_CANVAS, current)

        assertNotNull(store.getCanvas(CanvasName("imported_")), "duplicate name gets a '_' suffix")
    }

    @Test
    fun hereMergesCardsIntoCurrentCanvas() {
        val store = storeWithCurrent()
        store.saveCards(CanvasName("home"), ColumnId(1), cardsIn(ColumnId(1), "# existing\n"))
        val doc = TdzDocument(null, cardsIn(ColumnId(1), "# incoming\n"))

        val result = CanvasImporter(store).import("file", doc, ImportMode.HERE, current)

        assertEquals(2, store.cards(CanvasName("home"), ColumnId(1)).size, "incoming cards append to existing ones")
        assertEquals(CanvasName("home"), result.canvas)
        assertTrue(result.changedColumns.contains(ColumnId(1)))
    }

    @Test
    fun hereRemapsLayoutColumnCardsToFirstNonLayout() {
        val store = storeWithCurrent()
        // incoming cards target column 0, which is a layout in the current canvas
        val doc = TdzDocument(null, cardsIn(ColumnId(0), "# X\n"))

        CanvasImporter(store).import("file", doc, ImportMode.HERE, current)

        assertTrue(store.cards(CanvasName("home"), ColumnId(0)).isEmpty(), "layout column receives no cards")
        assertTrue(store.cards(CanvasName("home"), ColumnId(1)).isNotEmpty(), "cards remapped to the first non-layout column")
    }
}
