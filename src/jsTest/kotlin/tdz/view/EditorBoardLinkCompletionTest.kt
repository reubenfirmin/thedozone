package tdz.view

import tdz.model.CanvasName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorBoardLinkCompletionTest {

    private val boardA = CanvasName("a/x")
    private val boardB = CanvasName("b/x")
    private val boardWork = CanvasName("Work")
    private val boardHome = CanvasName("Home")
    private val boardProject = CanvasName("Project-Timeline")
    private val self = CanvasName("Current")

    @Test
    fun offersBoardsWhenInsideAnOpenBracket() {
        val boards = listOf(boardWork, boardHome, boardProject)
        val result = boardLinkCompletions("see [", boards, self = self)
        assertEquals(3, result.size, "all boards offered")
        // Sorted by absolutePath
        val paths = result.map { it.absolutePath }
        assertEquals(paths.sorted(), paths, "sorted by absolute path")
    }

    @Test
    fun offersBoardsWhileTypingAPhraseInsideTheBracket() {
        val boards = listOf(boardWork, boardHome, boardProject)
        val result = boardLinkCompletions("see [Pro", boards, self = self)
        assertTrue(result.any { it.leaf.contains("Project") || it.absolutePath.contains("Project") })
    }

    @Test
    fun silentOutsideAnyBracket() {
        val boards = listOf(boardWork)
        assertTrue(boardLinkCompletions("just some text", boards, self = self).isEmpty())
    }

    @Test
    fun silentAfterTheBracketIsClosed() {
        val boards = listOf(boardWork)
        assertTrue(boardLinkCompletions("see [Work] then", boards, self = self).isEmpty())
    }

    @Test
    fun excludesSelf() {
        val boards = listOf(boardWork, self)
        val result = boardLinkCompletions("[", boards, self = self)
        assertTrue(result.none { it.absolutePath == "/" + self.normalized() }, "self excluded")
        assertEquals(1, result.size)
    }

    @Test
    fun twoSharedLeafNamesAreDistinguishable() {
        // a/x and b/x both have leaf "x" but different absolute paths and breadcrumbs
        val boards = listOf(boardA, boardB)
        val result = boardLinkCompletions("[", boards, self = self)
        assertEquals(2, result.size, "both boards offered")
        val pathA = result.find { it.absolutePath == "/a/x" }
        val pathB = result.find { it.absolutePath == "/b/x" }
        requireNotNull(pathA) { "a/x present" }
        requireNotNull(pathB) { "b/x present" }
        assertEquals("x", pathA.leaf)
        assertEquals("x", pathB.leaf)
        assertEquals("a", pathA.breadcrumb)
        assertEquals("b", pathB.breadcrumb)
    }

    @Test
    fun absolutePathStartsWithSlash() {
        val boards = listOf(boardA, boardWork)
        val result = boardLinkCompletions("[", boards, self = self)
        assertTrue(result.all { it.absolutePath.startsWith("/") }, "all absolutePaths start with /")
    }

    @Test
    fun leafIsLastSegment() {
        val boards = listOf(boardA, boardWork)
        val result = boardLinkCompletions("[", boards, self = self)
        val resA = result.find { it.absolutePath == "/a/x" }
        val resWork = result.find { it.absolutePath == "/Work" }
        assertEquals("x", resA?.leaf)
        assertEquals("Work", resWork?.leaf)
    }

    @Test
    fun deduplicatesByAbsolutePath() {
        // Same board twice in list
        val boards = listOf(boardWork, boardWork)
        val result = boardLinkCompletions("[", boards, self = self)
        assertEquals(1, result.size, "de-duplicated")
    }

    @Test
    fun rootBoardHasEmptyBreadcrumb() {
        // A top-level board like "Work" has parentName = root, breadcrumb = ""
        val boards = listOf(boardWork)
        val result = boardLinkCompletions("[", boards, self = self)
        assertEquals(1, result.size)
        assertEquals("", result[0].breadcrumb)
    }
}
