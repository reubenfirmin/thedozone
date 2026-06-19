package tdz.view

import kotlinx.html.div
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.model.CanvasName
import tdz.model.Card
import tdz.model.CardMetadata
import tdz.model.ColumnId
import tdz.model.card.CardListItem
import tdz.model.card.ListGroup
import tdz.model.card.ListMemberType
import tdz.model.card.Text
import web.dom.document
import web.html.HTMLElement
import zoned.framework.interop.addToBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression: removing Tailwind converted the task marker's `span("inline-block pr-2")` into TWO
 * stacked css{} calls. css{} REPLACES the whole style attribute, so the second call (font-weight)
 * silently wiped the first (display/padding-right) and checkboxes lost the gap before their text.
 */
class TaskMarkerSpacingTest {

    @AfterTest
    fun cleanup() {
        document.body.asDynamic()?.innerHTML = ""
    }

    @Test
    fun taskMarkerKeepsPaddingBetweenCheckboxAndText() {
        val item = CardListItem(listOf(Text("buy milk")), level = 0, type = ListMemberType.BULLET, weight = 1, done = false)
        val card = Card(listOf(ListGroup(listOf(item), ListMemberType.BULLET, 0)), CardMetadata(ColumnId("0")), 0)
        addToBody {
            div {
                with(CardRenderer) {
                    render(CanvasName("test"), listOf(card), EventBus<Event>())
                }
            }
        }

        val marker = document.querySelector(".tdz-task label > span") as? HTMLElement
        assertNotNull(marker, "the task row should render a marker span inside its label")

        // Computed style, not the style attribute: the declarations moved from per-element inline
        // css{} into shared theme-keyed class rules — what must hold is what the user sees.
        val style = web.dom.getComputedStyle(marker)
        assertTrue(style.getPropertyValue("padding-right") == "8px",
            "marker keeps its 0.5rem gap before the task text (got '${style.getPropertyValue("padding-right")}')")
        assertTrue(style.getPropertyValue("display") == "inline-block",
            "marker keeps display: inline-block (got '${style.getPropertyValue("display")}')")
        val weight = style.getPropertyValue("font-weight")
        assertTrue(weight == "700" || weight == "bold", "marker stays bold (got '$weight')")
    }
}
