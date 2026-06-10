package tdz.model.localstorage

import tdz.model.ColumnId
import kotlinx.browser.window
import tdz.model.CanvasName
import tdz.model.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColumnModeStorageTest {

    private val fs = LocalStorageModel()
    private val canvas = CanvasName("_mode-test")

    @Test
    fun modeRoundTripsTyped() {
        fs.storeColumnMode(canvas, ColumnId(1), Mode.EDITOR)
        assertEquals(Mode.EDITOR, fs.getColumnMode(canvas, ColumnId(1)))
        fs.storeColumnMode(canvas, ColumnId(1), Mode.CARD)
        assertEquals(Mode.CARD, fs.getColumnMode(canvas, ColumnId(1)))
    }

    @Test
    fun garbageStoredValueReadsAsNullNotCrash() {
        window.localStorage.setItem("COLUMN_MODE${canvas.normalized()}-2", "NOT_A_MODE")
        assertNull(fs.getColumnMode(canvas, ColumnId(2)))
    }
}
