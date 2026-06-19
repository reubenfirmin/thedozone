package tdz.view

import kotlinx.browser.document
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.model.ColumnId
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ColumnContainerTest {

    @Test
    fun beforeRemovalRemovesColumnAccentStyle() {
        val columnId = ColumnId("4242")
        val styleEl = document.createElement("style")
        styleEl.id = "tdz-col-accent-$columnId"
        document.head?.appendChild(styleEl)
        assertNotNull(document.getElementById("tdz-col-accent-$columnId"))

        val canvas = canvasFromTemplate(CanvasName("_accent-test"), null, SINGLE_PANE)
        val bus = EventBus<Event>()
        val container = ColumnContainer(
            canvas, Mode.CARD, emptyList(), Boundary(0, 0, 100, 100), columnId, bus,
        ) { id -> EditorColumn(canvas, id, bus) }
        container.beforeRemoval()

        assertNull(
            document.getElementById("tdz-col-accent-$columnId"),
            "the injected accent <style> should be removed along with its column",
        )
    }
}
