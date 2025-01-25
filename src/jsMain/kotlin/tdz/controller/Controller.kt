package tdz.controller

import kotlinx.browser.document
import tdz.controller.events.EventBus
import tdz.controller.events.EventType.*
import tdz.controller.events.ToggleColumn
import tdz.model.*
import tdz.view.ColumnContainer
import web.html.HTMLElement
import zoned.framework.interop.repaint

class Controller(
    val model: Model,
    eventBus: EventBus) {

    private val downloader = Downloader(model)
    private val uploader = Uploader(model, eventBus)

    init {
        eventBus.on(TOGGLE_COLUMN) { e ->
            val columnId = (e as ToggleColumn).columnId
            val containerId = ColumnContainer.containerId(columnId)
            val div = document.getElementById(containerId)!! as HTMLElement
            val mode = div.getAttribute("mode")
            if (mode == "card") {
                // TODO get handle on ColumnContainer
                div.repaint {
                    // column container function
                }
            }
        }
    }
}