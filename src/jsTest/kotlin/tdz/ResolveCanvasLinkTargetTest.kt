package tdz

import tdz.model.CanvasName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolveCanvasLinkTargetTest {
    @Test fun relativeChild() =
        assertEquals("a/b/x", resolveCanvasLinkTarget(CanvasName("a/b"), "x").normalized())
    @Test fun relativeMultiSegment() =
        assertEquals("a/sub/child", resolveCanvasLinkTarget(CanvasName("a"), "sub/child").normalized())
    @Test fun relativeFromRoot() =
        assertEquals("x", resolveCanvasLinkTarget(rootCanvas, "x").normalized())
    @Test fun absolutePathFromRoot() =
        assertEquals("a/b/c", resolveCanvasLinkTarget(CanvasName("deep/here"), "/a/b/c").normalized())
    @Test fun dotDotGoesToParent() =
        assertEquals("a", resolveCanvasLinkTarget(CanvasName("a/b"), "..").normalized())
    @Test fun tildeAndSlashGoToRoot() {
        assertTrue(resolveCanvasLinkTarget(CanvasName("a/b"), "~").isRoot())
        assertTrue(resolveCanvasLinkTarget(CanvasName("a/b"), "/").isRoot())
    }
}
