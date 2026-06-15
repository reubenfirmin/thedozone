package tdz.view

import kotlinx.html.div
import tdz.controller.events.Event
import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.markdown.MarkdownService
import web.dom.document
import web.dom.getComputedStyle
import web.html.HTMLElement
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A card whose only content is an image renders full-bleed — the photo fills the card with no inner
 * padding — while ordinary cards keep their frame.
 */
class CardImageBleedTest {

    @AfterTest fun cleanup() { document.body.asDynamic()?.innerHTML = "" }

    private fun renderColumn(markdown: String) {
        val cards = MarkdownService.fromMd(markdown, ColumnId(1))
        addToBody { div { with(CardRenderer) { render(CanvasName("test"), cards, EventBus<Event>()) } } }
    }

    @Test
    fun imageOnlyCardIsFullBleed() {
        renderColumn("[https://example.com/photo.png (Photo)]\n\n# Notes\nplain text here\n")
        val image = document.querySelector(".tdz-card-image") as? HTMLElement
        assertNotNull(image, "the image-only card is marked full-bleed")
        val style = getComputedStyle(image)
        assertEquals("0px", style.getPropertyValue("padding"), "no inner padding")
        assertEquals("0px", style.getPropertyValue("margin"), "no outer margin")
    }

    @Test
    fun ordinaryCardsAreNotFullBleed() {
        renderColumn("# Notes\nplain text here\n")
        assertNull(document.querySelector(".tdz-card-image"), "a text card keeps its frame")
    }
}
