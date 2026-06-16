package tdz.view

import tdz.model.CanvasName
import kotlin.test.Test
import kotlin.test.assertEquals

class OmniboxTest {

    // ---- omniboxRowVisible (typeahead) ------------------------------------------

    @Test
    fun pinnedRowsAlwaysShow() {
        // Parent / all-boards / back rows have a null key and never filter out.
        assertEquals(true, omniboxRowVisible(null, ""))
        assertEquals(true, omniboxRowVisible(null, "anything"))
        assertEquals(true, omniboxRowVisible(null, "a/b"))
    }

    @Test
    fun blankQueryShowsEveryRow() {
        assertEquals(true, omniboxRowVisible("today", ""))
        assertEquals(true, omniboxRowVisible("today", "   "))
    }

    @Test
    fun filtersByCaseInsensitiveSubstring() {
        assertEquals(true, omniboxRowVisible("ideas", "idea"))
        assertEquals(true, omniboxRowVisible("backlog", "BACK"))
        assertEquals(false, omniboxRowVisible("today", "zzz"))
    }

    @Test
    fun aSlashIsPathModeSoBoardRowsHide() {
        // A slash means the user is typing a path to go/create, not filtering boards.
        assertEquals(false, omniboxRowVisible("today", "work/new"))
        assertEquals(false, omniboxRowVisible("today", "/today"))
    }

    // ---- omniboxLabel -----------------------------------------------------------

    @Test
    fun rootBoardLabelsAsHome() {
        assertEquals("Home", omniboxLabel(CanvasName("")))
        assertEquals("Home", omniboxLabel(CanvasName("/")))
    }

    @Test
    fun nonRootBoardLabelsAsLeaf() {
        assertEquals("today", omniboxLabel(CanvasName("work/today")))
        assertEquals("work", omniboxLabel(CanvasName("work")))
    }

    // ---- omniboxQueryIsPath -----------------------------------------------------

    @Test
    fun pathDetectionKeysOnSlash() {
        assertEquals(true, omniboxQueryIsPath("/work/today"))
        assertEquals(true, omniboxQueryIsPath("a/b"))
        assertEquals(false, omniboxQueryIsPath("today"))
        assertEquals(false, omniboxQueryIsPath("  today  "))
    }

    // ---- cycleIndex (highlight navigation) --------------------------------------

    @Test
    fun cyclingWrapsAtBothEnds() {
        assertEquals(1, cycleIndex(0, 3, 1))
        assertEquals(2, cycleIndex(1, 3, 1))
        assertEquals(0, cycleIndex(2, 3, 1), "stepping past the last wraps to the first")
        assertEquals(2, cycleIndex(0, 3, -1), "stepping before the first wraps to the last")
    }

    @Test
    fun cyclingEmptyListHasNoHighlight() {
        assertEquals(-1, cycleIndex(0, 0, 1))
        assertEquals(-1, cycleIndex(-1, 0, -1))
    }
}
