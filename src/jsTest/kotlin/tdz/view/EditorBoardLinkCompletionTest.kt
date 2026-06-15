package tdz.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorBoardLinkCompletionTest {

    private val boards = listOf("Work", "Home", "Project Timeline")

    @Test
    fun offersBoardsWhenInsideAnOpenBracket() {
        val result = boardLinkCompletions("see [", boards, self = "Current")
        assertEquals(listOf("Home", "Project Timeline", "Work"), result, "all boards, sorted")
    }

    @Test
    fun offersBoardsWhileTypingAPhraseInsideTheBracket() {
        // Ace narrows by the typed prefix; the helper just decides the pool is on.
        val result = boardLinkCompletions("see [Pro", boards, self = "Current")
        assertTrue(result.contains("Project Timeline"))
    }

    @Test
    fun silentOutsideAnyBracket() {
        assertTrue(boardLinkCompletions("just some text", boards, self = "Current").isEmpty())
    }

    @Test
    fun silentAfterTheBracketIsClosed() {
        assertTrue(boardLinkCompletions("see [Work] then", boards, self = "Current").isEmpty())
    }

    @Test
    fun excludesTheCurrentBoardAndBlanksAndDedupes() {
        val result = boardLinkCompletions("[", listOf("Work", "Work", "", "Current"), self = "Current")
        assertEquals(listOf("Work"), result, "no self-link, no blanks, de-duplicated")
    }
}
