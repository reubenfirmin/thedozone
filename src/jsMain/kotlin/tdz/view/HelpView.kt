package tdz.view

import kotlinx.html.TagConsumer
import tdz.controller.events.Event
import tdz.model.CanvasName
import tdz.model.markdown.MarkdownService
import tdz.view.help.HelpTopic
import web.html.HTMLElement
import zoned.framework.events.EventBus

/**
 * Renders a help topic as read-only cards into the overlay panel — the content the help fragments
 * swap into [Zones.OVERLAY]. Pulled out of [tdz.Index] (a render coordinator) so the help-rendering
 * mechanism lives in the view layer.
 *
 * Help content is read-only: its cards render through an inert [EventBus] with no subscribers, so
 * interactions (task-toggle, canvas-link navigation) fired by [CardRenderer] never reach the live
 * controller/model (which would mutate or throw against the synthetic "_help" canvas).
 *
 * NOTE: help markdown must not use bare canvas-link syntax outside code fences — CardRenderer's
 * canvas-link onMount still registers a Sortable drop zone regardless of the bus, so such links
 * would be a latent trap.
 */
class HelpView {

    private val helpEventBus = EventBus<Event>()

    fun TagConsumer<HTMLElement>.render(topic: HelpTopic) {
        overlayPanel(topic.title) {
            with(CardRenderer) {
                render(CanvasName("_help"), MarkdownService.fromMd(topic.markdown).flatten(), helpEventBus, loose = true)
            }
        }
    }
}
