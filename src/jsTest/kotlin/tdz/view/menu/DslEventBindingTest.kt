package tdz.view.menu

import kotlinx.html.div
import web.html.HTMLElement
import web.events.Event
import web.events.EventType
import zoned.framework.dom.Ref
import zoned.framework.dom.onContextMenu
import zoned.framework.dom.ref
import zoned.framework.interop.addToBody
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Confirms that zoned's DSL event handlers (onClick/onContextMenu/...) bind a LIVE listener
 * via consumer.onTagEvent — i.e. they are NOT pre-DOM. A contextmenu dispatched on a div
 * built with `onContextMenu { }` must invoke the handler.
 */
class DslEventBindingTest {

    @Test
    fun zonedDslOnContextMenuFiresLive() {
        var count = 0
        val ref = Ref<HTMLElement>()
        addToBody {
            div {
                ref(ref)
                onContextMenu { count++ }
            }
        }
        ref.element.dispatchEvent(Event(EventType("contextmenu")))
        assertEquals(1, count, "zoned DSL onContextMenu should bind a live listener")
    }
}
