package tdz.view

import kotlinx.html.div
import tdz.controller.events.EventBus
import tdz.controller.events.RefreshColumns
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import zoned.framework.interop.addToBody
import zoned.framework.routing.Zone.Companion.zone
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasViewLifecycleTest {

    private fun subscriptions(bus: EventBus) = bus.eventRegistry[RefreshColumns::class]?.size ?: 0

    @Test
    fun swapDetachesThePreviousRendersSubscription() {
        Zones.CANVAS.resolve()?.remove()   // other tests may have left a shell behind
        val bus = EventBus()
        val model = Model(CanvasName("_cv-lifecycle"))
        val canvas = canvasFromTemplate(CanvasName("_cv-lifecycle"), null, SINGLE_PANE)
        addToBody { div { zone(Zones.CANVAS) } }
        try {
            val view = CanvasView(bus, model)
            view.swap(canvas)
            assertEquals(1, subscriptions(bus), "each live render holds one subscription")

            view.swap(canvas)
            assertEquals(1, subscriptions(bus), "swap detaches the previous render's subscription")

            view.dispose()
            assertEquals(0, subscriptions(bus), "dispose detaches everything")
        } finally {
            Zones.CANVAS.resolve()?.remove()
            Zones.INTRO.resolve()?.remove()
        }
    }
}
