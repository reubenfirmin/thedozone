package tdz.view.menu

import kotlinx.html.div
import web.dom.document
import web.events.Event
import web.events.EventType
import web.html.HTMLElement
import zoned.framework.dom.Ref
import zoned.framework.dom.ref
import zoned.framework.interop.addToBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the real right-click path: [attachContextMenu] must bind a live `contextmenu`
 * listener (in onMount, on the real element). Dispatching a contextmenu event on the host
 * must open the menu. This is the regression test for "right-click does nothing" — which
 * happened when the binding used the pre-DOM kotlinx-html `onContextMenu` DSL setter.
 */
class ContextMenuAttachTest {

    @AfterTest
    fun cleanup() {
        ContextMenu.hide()
    }

    @Test
    fun rightClickOnAttachedElementOpensMenu() {
        val hostRef = Ref<HTMLElement>()
        addToBody {
            div {
                ref(hostRef)
                attachContextMenu { listOf(MenuSection(items = listOf(MenuItem("Download")))) }
            }
        }

        // Simulate a right-click on the host element.
        hostRef.element.dispatchEvent(Event(EventType("contextmenu")))

        val bodyText = document.body.textContent ?: ""
        assertTrue(bodyText.contains("Download"), "right-click should open the menu; body='$bodyText'")
    }
}
