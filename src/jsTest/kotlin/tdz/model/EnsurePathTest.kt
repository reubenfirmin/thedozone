package tdz.model

import tdz.ancestry
import tdz.controller.CanvasHierarchy
import tdz.rootCanvas
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnsurePathTest {
    @AfterTest fun clean() { kotlinx.browser.window.localStorage.clear() }

    @Test fun ancestryIsCumulativeFromRoot() {
        assertEquals(
            listOf("a", "a/b", "a/b/c"),
            ancestry(CanvasName("a/b/c")).map { it.normalized() },
        )
    }
    @Test fun ancestryOfRootIsEmpty() {
        // The route's root branch relies on ensurePath never running on root; lock the contract.
        assertEquals(emptyList(), ancestry(rootCanvas))
    }
    @Test fun ensurePathCreatesMissingChainWithParents() {
        val m = Model(rootCanvas)
        CanvasHierarchy.ensurePath(m, CanvasName("some/new/board"))
        assertTrue(m.exists(CanvasName("some")))
        assertTrue(m.exists(CanvasName("some/new")))
        assertTrue(m.exists(CanvasName("some/new/board")))
        assertEquals("some/new", m.getCanvas(CanvasName("some/new/board"))?.parent?.normalized())
        assertEquals("some", m.getCanvas(CanvasName("some/new"))?.parent?.normalized())
        // The first segment's parent is root (the empty path).
        assertTrue(m.getCanvas(CanvasName("some"))?.parent?.isRoot() == true)
    }
    @Test fun ensurePathIsIdempotent() {
        val m = Model(rootCanvas)
        m.updateColumn(CanvasName("a"), ColumnId(1), "# keep me\n")
        CanvasHierarchy.ensurePath(m, CanvasName("a/b"))   // re-ensuring 'a' must not clobber it
        // New boards get link cards appended; the original content must be preserved (not overwritten).
        // saveCards() round-trips through the card model (which adds column-metadata comments), so we
        // verify that the heading card is still there, not that the raw bytes are identical.
        val cards = m.cards(CanvasName("a"), ColumnId(1))
        assertTrue(cards.isNotEmpty(), "existing column content must be preserved (not clobbered)")
        val headings = cards.flatMap { it.segments }.filterIsInstance<tdz.model.card.CardHeading>()
        assertTrue(headings.any { it.text.trim() == "keep me" }, "heading 'keep me' must be preserved")
    }
}
