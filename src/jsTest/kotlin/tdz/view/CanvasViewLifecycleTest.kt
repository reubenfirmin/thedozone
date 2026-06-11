package tdz.view

import kotlinx.html.div
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.RefreshColumns
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import zoned.framework.interop.addToBody
import zoned.framework.routing.Zone.Companion.zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CanvasViewLifecycleTest {

    private fun subscriptions(bus: EventBus<Event>) = bus.eventRegistry[RefreshColumns::class]?.size ?: 0

    @Test
    fun swapDetachesThePreviousRendersSubscription() {
        Zones.CANVAS.resolve()?.remove()   // other tests may have left a shell behind
        val bus = EventBus<Event>()
        val model = Model(CanvasName("_cv-lifecycle"))
        val canvas = canvasFromTemplate(CanvasName("_cv-lifecycle"), null, SINGLE_PANE)
        addToBody { div { zone(Zones.CANVAS) } }
        try {
            val view = CanvasView(bus, model)
            assertNull(view.columns, "no render yet -> no columns")
            assertNull(view.currentModel, "no render yet -> no model")
            view.swap(canvas)
            assertEquals(1, subscriptions(bus), "each live render holds one subscription")
            assertNotNull(view.columns, "a rendered view exposes its live columns")
            assertEquals(canvas, view.currentModel, "a rendered view exposes what it rendered")

            view.swap(canvas)
            assertEquals(1, subscriptions(bus), "swap detaches the previous render's subscription")

            view.dispose()
            assertEquals(0, subscriptions(bus), "dispose detaches everything")
            assertNull(view.columns, "dispose clears the columns handle")
            assertNull(view.currentModel, "dispose clears the model handle")
        } finally {
            Zones.CANVAS.resolve()?.remove()
            Zones.INTRO.resolve()?.remove()
        }
    }
}
