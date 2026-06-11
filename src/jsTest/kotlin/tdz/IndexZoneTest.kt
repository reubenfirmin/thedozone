package tdz

import kotlinx.html.div
import kotlinx.html.id
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshCanvas
import tdz.model.Model
import tdz.view.Zones
import zoned.framework.interop.addToBody
import zoned.framework.interop.getHtmlElement
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class IndexZoneTest {

    @Test
    fun refreshCanvasSwapsOnlyTheCanvasZone() {
        val bus = EventBus<Event>()
        val model = Model(rootCanvas)
        val index = Index(model, bus)
        try {
            index.index(model.currentCanvas())
            assertNotNull(Zones.CANVAS.resolve(), "canvas zone is mounted")
            val contentBefore = Zones.CANVAS.resolve()?.firstElementChild
            assertNotNull(contentBefore, "canvas content is rendered into the zone")

            // A body-level sibling (e.g. the sync badge) must survive a canvas refresh.
            addToBody { div { id = "tdz-test-sibling" } }

            bus.fire(RefreshCanvas(model.currentCanvas().name, structural = false))

            assertNotNull(getHtmlElement("tdz-test-sibling"), "body-level sibling survives RefreshCanvas")
            val contentAfter = Zones.CANVAS.resolve()?.firstElementChild
            assertNotNull(contentAfter, "canvas content is re-rendered")
            assertNotSame(contentBefore, contentAfter, "the zone's content was actually swapped")
            assertTrue(
                (Zones.CANVAS.resolve()?.childElementCount ?: 0) > 0,
                "swap rendered into the stable zone element",
            )
        } finally {
            getHtmlElement("tdz-test-sibling")?.remove()
            Zones.CANVAS.resolve()?.remove()
            Zones.INTRO.resolve()?.remove()   // root canvas may be pristine -> welcome card shown
        }
    }
}
