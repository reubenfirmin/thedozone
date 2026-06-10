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
        val back = SyncStateDoc.decode(doc.encode())
        assertEquals(doc.entries, back.entries)
    }

    @Test
    fun emptyRoundTrips() {
        val back = SyncStateDoc.decode(SyncStateDoc(emptyMap()).encode())
        assertEquals(emptyMap(), back.entries)
    }

    @Test
    fun withHelpersUpdateAndRemove() {
        val a = SyncStateDoc(emptyMap()).with("/p.md", SyncEntry("r1", 1.0))
        assertEquals(SyncEntry("r1", 1.0), a.entries["/p.md"])
        val b = a.without("/p.md")
        assertNull(b.entries["/p.md"])
    }

    @Test
    fun roundTripsBaseContent() {
        val doc = SyncStateDoc.EMPTY.with("/n/_/col-0.md", SyncEntry("rev1", 123.0, base = "# Card\nbody\n"))
        val restored = SyncStateDoc.decode(doc.encode())
        assertEquals("# Card\nbody\n", restored.entries["/n/_/col-0.md"]?.base)
        assertEquals("rev1", restored.entries["/n/_/col-0.md"]?.rev)
    }

    @Test
    fun toleratesLegacyEntriesWithoutBase() {
        // A doc serialized before `base` existed: deserializing must yield base == null, not crash.
        val legacy = """{"/n/_/col-0.md":{"rev":"r","localUpdatedAt":5}}"""
        val restored = SyncStateDoc.decode(legacy)
        assertEquals(null, restored.entries["/n/_/col-0.md"]?.base)
        assertEquals("r", restored.entries["/n/_/col-0.md"]?.rev)
    }

    @Test
    fun toleratesLegacyExplicitNulls() {
        // The old serializer wrote nulls explicitly; both shapes must decode.
        val legacy = """{"/p.md":{"rev":null,"localUpdatedAt":5,"base":null}}"""
        val restored = SyncStateDoc.decode(legacy)
        assertEquals(null, restored.entries["/p.md"]?.rev)
        assertEquals(5.0, restored.entries["/p.md"]?.localUpdatedAt)
    }

    @Test
    fun roundTripsVersionVector() {
        val doc = SyncStateDoc.EMPTY.with("/n/_/col-0.md",
            SyncEntry("r", 1.0, base = "b\n", vv = mapOf("dev1" to 3L)))
        val restored = SyncStateDoc.decode(doc.encode())
        assertEquals(mapOf("dev1" to 3L), restored.entries["/n/_/col-0.md"]?.vv)
    }

    @Test
    fun legacyEntryHasEmptyVv() {
        val legacy = """{"/n/_/col-0.md":{"rev":"r","localUpdatedAt":5}}"""
        val restored = SyncStateDoc.decode(legacy)
        assertEquals(emptyMap(), restored.entries["/n/_/col-0.md"]?.vv)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        // Forward compatibility: a newer build's extra field must not break this one.
        val future = """{"/p.md":{"rev":"r","localUpdatedAt":5,"someFutureField":true}}"""
        assertEquals("r", SyncStateDoc.decode(future).entries["/p.md"]?.rev)
    }
}
