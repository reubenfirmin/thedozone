package tdz

import tdz.model.CanvasName
import zoned.framework.routing.Router
import kotlinx.browser.window
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PathRouteTest {
    @AfterTest fun cleanup() { web.dom.document.body.asDynamic()?.innerHTML = ""; window.localStorage.clear() }

    @Test fun navigatingAMultiSegmentUrlBuildsTheChainAndLandsOnLeaf() {
        Router.mount(canvasPages, helpPages, settingsPages)
        Router.navigate("/some/new/board")
        assertTrue(tdzModel.exists(CanvasName("some")), "ancestor 'some' created")
        assertTrue(tdzModel.exists(CanvasName("some/new")), "ancestor 'some/new' created")
        assertTrue(tdzModel.exists(CanvasName("some/new/board")), "leaf created")
        assertEquals("some/new/board", tdzModel.currentCanvas().name.normalized(), "landed on leaf")
    }

    @Test fun namespacedDuplicatesCoexist() {
        Router.mount(canvasPages, helpPages, settingsPages)
        Router.navigate("/a/x")
        Router.navigate("/b/x")
        assertTrue(tdzModel.exists(CanvasName("a/x")))
        assertTrue(tdzModel.exists(CanvasName("b/x")))
    }
}
