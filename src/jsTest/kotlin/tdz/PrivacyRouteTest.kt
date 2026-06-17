package tdz

import tdz.controller.events.Event
import tdz.model.Model
import tdz.view.Zones
import zoned.framework.events.EventBus
import zoned.framework.routing.Router
import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrivacyRouteTest {
    @AfterTest fun cleanup() { web.dom.document.body.asDynamic()?.innerHTML = ""; window.localStorage.clear() }

    @Test fun privacyIsACleanTopLevelRouteRenderingThePolicy() {
        Router.mount(canvasPages, helpPages, settingsPages, legalPages)
        val index = Index(Model(rootCanvas), EventBus<Event>())
        index.index(Model(rootCanvas).currentCanvas())

        Router.navigate(legalPages.privacy.path())
        assertEquals("/privacy", window.location.pathname, "privacy lives at the clean top-level /privacy")
        val overlay = Zones.OVERLAY.resolve()
        assertTrue((overlay?.childElementCount ?: 0) > 0, "the privacy panel rendered into the overlay zone")
        assertTrue(overlay?.textContent?.contains("never see your data") == true,
            "the privacy policy text rendered in the panel")
    }
}
