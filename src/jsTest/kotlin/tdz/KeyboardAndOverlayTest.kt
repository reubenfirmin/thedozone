package tdz

import kotlinx.browser.window
import tdz.controller.events.ClearDone
import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.model.Model
import tdz.view.Overlay
import tdz.view.Zones
import web.dom.document
import web.keyboard.KeyboardEvent
import web.keyboard.KeyboardEventInit
import web.mouse.MouseEvent
import web.mouse.MouseEventInit
import zoned.framework.interop.getHtmlElement
import zoned.framework.routing.Router
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import web.events.EventType

/**
 * The document-level interaction surface: keyboard shortcuts dispatched as real KeyboardEvents and
 * the overlay scrim's click-to-close discrimination — previously the largest untested code path.
 */
class KeyboardAndOverlayTest {

    private fun keydown(key: String, ctrl: Boolean = false, meta: Boolean = false) {
        document.dispatchEvent(KeyboardEvent(
            EventType("keydown"),
            KeyboardEventInit(key = key, ctrlKey = ctrl, metaKey = meta, cancelable = true),
        ))
    }

    @AfterTest
    fun cleanup() {
        Zones.OVERLAY.resolve()?.remove()
        Zones.CANVAS.resolve()?.remove()
        Zones.INTRO.resolve()?.remove()
        window.history.replaceState(null, "", "/")
    }

    @Test
    fun ctrlSemicolonFiresClearDone() {
        val bus = EventBus<Event>()
        val index = Index(Model(rootCanvas), bus)
        index.index(Model(rootCanvas).currentCanvas())
        var cleared = false
        bus.on<ClearDone> { cleared = true }

        keydown(";", ctrl = true)

        assertTrue(cleared, "Ctrl+; clears done tasks")
    }

    @Test
    fun helpShortcutOpensTheOverlayAndEscapeClosesIt() {
        Router.mount(canvasPages, helpPages, settingsPages, legalPages)
        val bus = EventBus<Event>()
        val index = Index(Model(rootCanvas), bus)
        index.index(Model(rootCanvas).currentCanvas())

        keydown("F1")
        assertEquals("/help/shortcuts", window.location.pathname, "F1 deep-links the shortcuts overlay")
        assertTrue((Zones.OVERLAY.resolve()?.childElementCount ?: 0) > 0, "the help panel rendered into the overlay zone")

        keydown("Escape")
        // The root canvas's own URL is "/_" (its name); the contract is leaving /help/*.
        assertFalse(window.location.pathname.startsWith("/help"), "Escape returns to the canvas route")
        assertEquals(0, Zones.OVERLAY.resolve()?.childElementCount, "the overlay zone was emptied")
    }

    @Test
    fun scrimClickClosesButPanelClickDoesNot() {
        Router.mount(canvasPages, helpPages, settingsPages, legalPages)
        val bus = EventBus<Event>()
        val index = Index(Model(rootCanvas), bus)
        index.index(Model(rootCanvas).currentCanvas())
        Router.navigate(helpPages.shortcuts.path())
        val overlay = Zones.OVERLAY.resolve()!!
        val panel = overlay.firstElementChild!!

        // A click INSIDE the panel bubbles to the zone with an inner target — must NOT close.
        panel.dispatchEvent(MouseEvent(EventType("click"), MouseEventInit(bubbles = true)))
        assertEquals("/help/shortcuts", window.location.pathname, "panel clicks don't dismiss")
        assertTrue(overlay.childElementCount > 0)

        // A click on the scrim itself closes (navigates back to the canvas).
        overlay.dispatchEvent(MouseEvent(EventType("click"), MouseEventInit(bubbles = true)))
        assertFalse(window.location.pathname.startsWith("/help"), "scrim clicks dismiss the overlay")
        assertEquals(0, Zones.OVERLAY.resolve()?.childElementCount)
    }
}
