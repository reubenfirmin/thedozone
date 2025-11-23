package tdz.controller

import kotlinx.html.div
import tdz.controller.events.EventBus
import tdz.controller.events.TaskToggleDone
import tdz.model.Model
import tdz.view.CardRenderer.renderSegment
import web.html.HTMLElement
import zoned.framework.interop.appendTo
import zoned.framework.interop.clear

class Controller(eventBus: EventBus, model: Model) {

    init {
        eventBus.on<TaskToggleDone> { event ->
            model.toggleDone(event.canvasName, event.card, event.segment)

            val targetElement = event.sourceEvent.target as HTMLElement
            val parent = targetElement.parentElement!!
            parent.clear()
            parent.appendTo().div {
                renderSegment(event.canvasName, event.segment, event.card, 0, eventBus)
            }
        }
    }
}