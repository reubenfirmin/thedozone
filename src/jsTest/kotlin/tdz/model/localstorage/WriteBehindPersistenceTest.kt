package tdz.model.localstorage

import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.Model
import web.events.Event
import web.events.EventType
import web.storage.localStorage
import web.window.window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence is write-behind: a column save lands in a shared in-memory buffer and the
 * synchronous localStorage.setItem happens AFTER the interaction's task (or on pagehide /
 * backgrounding), so it never sits between a click and its render. Reads always consult the
 * buffer first — every Model instance sees the latest write immediately, flushed or not.
 */
class WriteBehindPersistenceTest {

    private val canvas = CanvasName("_wb-test")
    private val column = ColumnId("1")

    private fun rawStored(): String? {
        // The raw key format is LocalStorageModel-internal; find our column by scanning.
        for (i in 0 until localStorage.length) {
            val key = localStorage.key(i) ?: continue
            if ("_wb-test" in key && key.startsWith(Namespace.COLUMN.toString())) {
                return localStorage.getItem(key)
            }
        }
        return null
    }

    @Test
    fun writeIsBufferedThenDurableOnPagehide() {
        val model = Model(canvas)
        model.updateColumn(canvas, column, "# Buffered\n")

        assertTrue(rawStored()?.contains("Buffered") != true,
            "the write must not hit localStorage synchronously (it buffers)")
        assertEquals(1, model.cards(canvas, column).size, "reads see the buffered write immediately")

        window.dispatchEvent(Event(EventType("pagehide")))
        assertTrue(rawStored()?.contains("Buffered") == true,
            "pagehide must flush the buffer — leaving the page cannot lose writes")
    }

    @Test
    fun otherModelInstancesSeeTheBufferedWrite() {
        val model = Model(canvas)
        model.updateColumn(canvas, column, "# Shared\n\n# Buffer\n")
        val other = Model(canvas)
        assertEquals(2, other.cards(canvas, column).size,
            "the buffer is storage-level (shared), not per-Model — readers can never go stale")
        window.dispatchEvent(Event(EventType("pagehide")))
    }

    @Test
    fun deleteCancelsAPendingWrite() {
        val model = Model(canvas)
        model.updateColumn(canvas, column, "# Doomed\n")
        model.deleteColumn(canvas, column)
        window.dispatchEvent(Event(EventType("pagehide")))
        assertTrue(rawStored()?.contains("Doomed") != true, "a delete must also discard the buffered write")
        assertNull(model.getCanvasColumnRaw(canvas, column))
    }
}
