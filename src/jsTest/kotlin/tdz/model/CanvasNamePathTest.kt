package tdz.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class CanvasNamePathTest {
    @Test fun normalizedKeepsSlashesButStripsPerSegment() {
        assertEquals("some/new/My-Board", CanvasName("some/new/My Board").normalized())
    }
    @Test fun collapsesEmptyAndEdgeSlashes() {
        assertEquals("a/b", CanvasName("/a//b/").normalized())
    }
    @Test fun segmentsSplitOnSlash() {
        assertEquals(listOf("a", "b", "c"), CanvasName("/a/b/c").segments())
    }
    @Test fun leafIsLastSegmentForDisplay() {
        assertEquals("My Board", CanvasName("some/new/My-Board").leaf())
    }
    @Test fun parentNameDropsLastSegment() {
        assertEquals("a/b", CanvasName("a/b/c").parentName().normalized())
        assertTrue(CanvasName("a").parentName().isRoot())
    }
    @Test fun childAppendsSegment() {
        assertEquals("a/b", CanvasName("a").child("b").normalized())
    }
    @Test fun rootIsEmpty() {
        assertTrue(CanvasName("").isRoot())
        assertFalse(CanvasName("a").isRoot())
        assertEquals("", CanvasName("").normalized())
    }
}
