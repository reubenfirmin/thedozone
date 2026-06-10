package tdz.model.localstorage

import tdz.model.CanvasName
import tdz.model.ColumnLayoutSpec
import tdz.model.Split
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class CanvasDocTest {

    private val doc = CanvasDoc(
        name = CanvasName("work"),
        parentName = CanvasName("_"),
        columns = listOf(0, 1, 2),
        layouts = listOf(ColumnLayoutSpec(0, Split.VERTICAL, listOf(1, 2))),
    )

    @Test
    fun roundTrips() {
        val back = CanvasDoc.decode(doc.encode())
        assertEquals(doc, back)
    }

    @Test
    fun decodesLegacyHandWrittenShape() {
        // The exact shape the old kotlin.js.Json serializer produced (explicit null parentId,
        // comma-joined columns, pipe-delimited layouts).
        val legacy = """{"name":"_","parentId":null,"columns":"0,1","layouts":"_|0|HORIZONTAL|1"}"""
        val back = CanvasDoc.decode(legacy)
        assertEquals(CanvasName("_"), back.name)
        assertEquals(null, back.parentName)
        assertEquals(listOf(0, 1), back.columns)
        assertEquals(listOf(ColumnLayoutSpec(0, Split.HORIZONTAL, listOf(1))), back.layouts)
    }

    @Test
    fun corruptDocumentFailsWithSerializationErrorNotClassCast() {
        // A missing required field must produce a descriptive decode error (schema enforcement),
        // not a downstream ClassCastException.
        assertFails { CanvasDoc.decode("""{"name":"x"}""") }
        assertFails { CanvasDoc.decode("not json") }
    }

    @Test
    fun unparseableLayoutEntriesAreSkipped() {
        val back = CanvasDoc.decode("""{"name":"x","columns":"0","layouts":"garbage"}""")
        assertEquals(emptyList(), back.layouts)
    }
}
