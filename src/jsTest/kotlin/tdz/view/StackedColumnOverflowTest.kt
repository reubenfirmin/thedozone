package tdz.view

import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.Event
import tdz.model.Canvas
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.CardMetadata
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Model
import tdz.model.Split
import tdz.model.card.CardHeading
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import zoned.framework.interop.getHtmlElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A STACKED (Split.HORIZONTAL) layout shares the height between its cells as equal rows. When the
 * TOP cell is overlong — whether a leaf column OR a NESTED layout — it must NOT push the BOTTOM
 * cell down out of view: every grid cell carries min-height:0 (the block-axis analogue of the
 * min-width:0 the columns already had) so a content-heavy cell shrinks to its track instead of
 * overflowing it, and leaf columns absorb the overflow with an internal (invisible) scroll.
 *
 * A leaf is a scroll container (overflow), so its own min-height:auto is already 0 — but a nested
 * LAYOUT div is not, so without an explicit min-height:0 its min-content height (all its cards)
 * forces its row taller than its track and shoves the sibling row off-screen. That nested case is
 * what this reproduces.
 */
class StackedColumnOverflowTest {

    @AfterTest fun cleanup() { getHtmlElement("stacked-host")?.remove() }

    private fun tallCards(n: Int): List<Card> =
        (0 until n).map { i -> Card(listOf(CardHeading("Card $i", 2)), CardMetadata(ColumnId("x")), index = i) }

    @Test
    fun overlongTopCellScrollsAndDoesNotPushBottomColumn() {
        val bus = EventBus<Event>()
        val name = CanvasName("_stacked-test")
        val model = Model(name)

        // root (stacked) -> [ nested (side-by-side) , bottom ]
        // nested (side-by-side) -> [ a , b ]   (both overlong)
        val nested = ColumnId("nested")
        val a = ColumnId("a")
        val b = ColumnId("b")
        val bottom = ColumnId("bottom")
        val canvas = Canvas(name, null,
            listOf(ColumnId.ROOT, nested, a, b, bottom),
            listOf(
                ColumnLayoutSpec(ColumnId.ROOT, Split.HORIZONTAL, listOf(nested, bottom)),
                ColumnLayoutSpec(nested, Split.VERTICAL, listOf(a, b)),
            ))
        val leaves = listOf(a, b, bottom)

        val columns = CanvasColumns(canvas, bus, model, isMobile = { false }) { id ->
            if (id == bottom) tallCards(1) else tallCards(60)
        }
        addToBody {
            div {
                id = "stacked-host"
                with(columns) { render(leaves) }
            }
        }
        // The real app constrains the board to a fixed-height, clipped container (the CANVAS zone);
        // reproduce that so the grid rows have a definite height to divide.
        val host = getHtmlElement("stacked-host")!!
        host.style.height = "600px"
        host.style.width = "800px"
        host.style.overflow = "hidden"

        val nestedEl = getHtmlElement(DomIds.column(nested))
        val topEl = getHtmlElement(DomIds.column(a))
        val bottomEl = getHtmlElement(DomIds.column(bottom))
        assertNotNull(nestedEl, "nested layout cell rendered")
        assertNotNull(topEl, "top leaf column rendered")
        assertNotNull(bottomEl, "bottom column rendered")

        val hostRect = host.getBoundingClientRect()
        val nestedRect = nestedEl.getBoundingClientRect()
        val bottomRect = bottomEl.getBoundingClientRect()

        // The overlong top cell (a nested layout) stays bounded to its row (~half the host), not
        // stretched to the height of all its cards.
        assertTrue(
            nestedRect.height < hostRect.height / 2 + 20,
            "top cell overflowed its row (height=${nestedRect.height}, host=${hostRect.height})",
        )

        // The bottom row stays INSIDE the host — borders/box-model must not push it past the
        // viewport (that displacement, accumulated down a nesting chain, hides the bottom column).
        assertTrue(
            bottomRect.bottom <= hostRect.bottom + 1,
            "bottom column pushed past the host (bottom=${bottomRect.bottom}, host bottom=${hostRect.bottom})",
        )

        // The leaf inside the top cell absorbs its overflow internally (wheel/touch scroll).
        assertTrue(
            topEl.scrollHeight > topEl.clientHeight,
            "overlong leaf must be internally scrollable (scrollHeight=${topEl.scrollHeight}, " +
                "clientHeight=${topEl.clientHeight})",
        )
    }
}
