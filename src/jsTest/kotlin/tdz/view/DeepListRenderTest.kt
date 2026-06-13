package tdz.view

import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.Event
import tdz.model.Card
import tdz.model.CanvasName
import tdz.model.CardMetadata
import tdz.model.ColumnId
import tdz.model.card.CardListItem
import tdz.model.card.ListGroup
import tdz.model.card.ListMemberType
import tdz.model.card.Text
import web.dom.document
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import zoned.framework.interop.getHtmlElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A malformed/pathologically-deep ListGroup must NOT take down the app. A board went blank because
 * one card's list rendering recursed without bound (22s of DOM-building, then a stack overflow that
 * aborted the WHOLE board render). The renderer must cap nesting depth and render the rest, so a
 * single bad card degrades to a truncated card — never a crash.
 */
class DeepListRenderTest {

    @AfterTest fun cleanup() { document.body?.asDynamic()?.innerHTML = "" }

    private fun item(text: String, level: Int) =
        CardListItem(listOf(Text(text)), level, ListMemberType.BULLET, weight = 1, done = false)

    @Test
    fun pathologicallyDeepListRendersTruncatedNotOverflowing() {
        // Each level carries a visible leaf AND nests deeper, far past anything toGroup can produce
        // (it caps at depth 2). The cap must TRUNCATE: render a bounded number of rows, not recurse
        // to a stack overflow. A weak "something rendered" assertion would false-pass on a partial
        // render before the overflow — so assert the COUNT is bounded.
        var group = ListGroup(listOf(item("leaf", 0)), ListMemberType.BULLET, 0)
        repeat(5000) { group = ListGroup(listOf(item("leaf", 0), group), ListMemberType.BULLET, 0) }
        val card = Card(listOf(group), CardMetadata(ColumnId(1)), index = 0)

        val bus = EventBus<Event>()
        addToBody {
            div {
                id = "deep-host"
                with(CardRenderer) { render(CanvasName("_deep"), listOf(card), bus) }
            }
        }

        val rows = (getHtmlElement("deep-host")?.querySelectorAll(".${CssClasses.TASK}")?.length ?: 0)
        assertTrue(rows in 1..200,
            "render must be DEPTH-BOUNDED (got $rows rows) — unbounded means it overflowed/hung")
    }
}
