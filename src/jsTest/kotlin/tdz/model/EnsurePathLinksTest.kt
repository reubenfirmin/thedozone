package tdz.model

import tdz.controller.CanvasHierarchy
import tdz.rootCanvas
import tdz.model.card.CanvasLink
import tdz.model.card.CardText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnsurePathLinksTest {
    @AfterTest fun clean() { kotlinx.browser.window.localStorage.clear() }

    private fun cards(m: Model, name: CanvasName): List<Card> {
        val c = m.getCanvas(name)!!
        return m.cards(name, c.firstNonLayout())
    }

    private fun canvasLinks(m: Model, name: CanvasName): List<String> =
        cards(m, name)
            .flatMap { it.segments.filterIsInstance<CardText>() }
            .flatMap { it.text.filterIsInstance<CanvasLink>() }
            .map { it.target }

    @Test fun createdBoardsGetParentAndChildLinks() {
        val m = Model(rootCanvas)
        CanvasHierarchy.ensurePath(m, CanvasName("what/is/this"))
        assertTrue(canvasLinks(m, CanvasName("what")).contains("is"), "parent lists its child")
        assertTrue(canvasLinks(m, CanvasName("what/is")).contains("this"), "mid lists its child")
        assertTrue(canvasLinks(m, CanvasName("what/is")).contains(".."), "mid has a parent link")
        assertTrue(canvasLinks(m, CanvasName("what/is/this")).contains(".."), "leaf has a parent link")
        assertTrue(canvasLinks(m, rootCanvas).contains("what"), "root lists the top-level board")
    }

    @Test fun insertionIsIdempotent() {
        val m = Model(rootCanvas)
        CanvasHierarchy.ensurePath(m, CanvasName("what/is"))
        CanvasHierarchy.ensurePath(m, CanvasName("what/is"))            // revisit
        CanvasHierarchy.ensurePath(m, CanvasName("what/is/this"))       // extend
        assertEquals(1, canvasLinks(m, CanvasName("what")).count { it == "is" }, "child link not duplicated on revisit")
    }
}
