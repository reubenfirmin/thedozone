package tdz

import tdz.model.CanvasName
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasUrlTest {
    @Test
    fun `empty path is root`() {
        assertEquals("/", canvasUrlFromPath(emptyList()))
    }

    @Test
    fun `segments join normalized with slashes`() {
        // "a" and "b" are already normalized, so they pass through unchanged.
        assertEquals("/a/b", canvasUrlFromPath(listOf(CanvasName("a"), CanvasName("b"))))
    }

    @Test
    fun `segments are normalized in the url`() {
        // "My Board" normalizes to "My-Board" (spaces become hyphens), proving normalization is applied.
        assertEquals("/My-Board", canvasUrlFromPath(listOf(CanvasName("My Board"))))
    }
}
