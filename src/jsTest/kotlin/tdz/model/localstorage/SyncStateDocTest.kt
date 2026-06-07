package tdz.model.localstorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncStateDocTest {

    @Test
    fun roundTripsEntries() {
        val doc = SyncStateDoc(
            mapOf(
                "/notes/_/col-0.md" to SyncEntry(rev = "a1", localUpdatedAt = 100.0),
                "/notes/_/_canvas.json" to SyncEntry(rev = null, localUpdatedAt = 200.0),
            )
        )
        val back = SyncStateDoc.fromJson(doc.toJson())
        assertEquals(doc.entries, back.entries)
    }

    @Test
    fun emptyRoundTrips() {
        val back = SyncStateDoc.fromJson(SyncStateDoc(emptyMap()).toJson())
        assertEquals(emptyMap(), back.entries)
    }

    @Test
    fun withHelpersUpdateAndRemove() {
        val a = SyncStateDoc(emptyMap()).with("/p.md", SyncEntry("r1", 1.0))
        assertEquals(SyncEntry("r1", 1.0), a.entries["/p.md"])
        val b = a.without("/p.md")
        assertNull(b.entries["/p.md"])
    }
}
