package tdz.model

import kotlinx.browser.window
import tdz.rootCanvas
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootCanvasTest {
    @AfterTest fun clean() { window.localStorage.clear() }

    @Test fun rootIsEmptyPath() {
        assertTrue(rootCanvas.isRoot(), "rootCanvas must be the empty path")
        assertEquals("", rootCanvas.normalized())
    }

    @Test fun freshModelBootstrapsRoot() {
        val m = Model(rootCanvas)
        m.currentCanvas()   // triggers root bootstrap
        assertTrue(m.exists(rootCanvas), "currentCanvas() creates root on a fresh model")
    }
}
