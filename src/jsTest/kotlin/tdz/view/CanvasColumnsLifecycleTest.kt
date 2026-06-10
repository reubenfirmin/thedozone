package tdz.view

import tdz.controller.events.EventBus
import tdz.controller.events.RefreshColumns
import tdz.model.CanvasName
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasColumnsLifecycleTest {

    private fun subscriptions(bus: EventBus) = bus.eventRegistry[RefreshColumns::class]?.size ?: 0

    @Test
    fun disposeRemovesRefreshColumnsSubscription() {
        val bus = EventBus()
        val model = Model(CanvasName("_cc-lifecycle"))
        val canvas = canvasFromTemplate(CanvasName("_cc-lifecycle"), null, SINGLE_PANE)

        val first = CanvasColumns(canvas, bus, model) { emptyList() }
        val second = CanvasColumns(canvas, bus, model) { emptyList() }
        assertEquals(2, subscriptions(bus), "each live instance holds one subscription")

        first.dispose()
        assertEquals(1, subscriptions(bus), "dispose removes the abandoned instance's subscription")

        first.dispose()
        assertEquals(1, subscriptions(bus), "dispose is idempotent")

        second.dispose()
        assertEquals(0, subscriptions(bus))
    }
}
