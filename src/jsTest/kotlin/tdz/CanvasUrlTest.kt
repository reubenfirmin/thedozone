package tdz

import tdz.model.CanvasName
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasUrlTest {
    @Test fun rootIsSlash() = assertEquals("/", canvasUrl(CanvasName("")))
    @Test fun singleSegment() = assertEquals("/My-Board", canvasUrl(CanvasName("My Board")))
    @Test fun multiSegmentJoins() = assertEquals("/some/new/board", canvasUrl(CanvasName("some/new/board")))
}
