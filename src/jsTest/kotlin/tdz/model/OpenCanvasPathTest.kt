package tdz.model

import tdz.controller.Controller
import tdz.controller.events.Event
import tdz.rootCanvas
import zoned.framework.events.EventBus
import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Controller's board-opening dispatch ([Controller.openCanvasPath], called by the board route):
 * opening a path builds the missing chain (via the hierarchy service) and makes the leaf current;
 * navigating up to root materializes it rather than landing on a null board.
 */
class OpenCanvasPathTest {
    @AfterTest fun cleanup() { window.localStorage.clear() }

    private fun controllerOver(model: Model) = Controller(EventBus<Event>(), model)

    @Test fun openingAPathBuildsTheChainAndLandsOnLeaf() {
        val model = Model(rootCanvas)
        val landed = controllerOver(model).openCanvasPath(CanvasName("some/new/board"))
        assertTrue(model.exists(CanvasName("some/new/board")))
        assertEquals("some/new/board", landed.name.normalized())
        assertEquals("some/new/board", model.currentCanvas().name.normalized())
    }

    @Test fun navigatingUpToRootMaterializesItRatherThanCrashing() {
        // Cold deep-link to a non-root board never creates root (the chain's ancestry excludes it).
        // Opening root must still land on a real board, not null.
        val model = Model(rootCanvas)
        val controller = controllerOver(model)
        controller.openCanvasPath(CanvasName("a/b"))
        assertEquals("a/b", model.currentCanvas().name.normalized())

        val root = controller.openCanvasPath(rootCanvas)   // PageUp eventually reaches root
        assertTrue(model.exists(rootCanvas), "root is materialized on navigation")
        assertTrue(root.name.isRoot(), "landed on root, not a null board")
    }
}
