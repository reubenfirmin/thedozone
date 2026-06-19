package tdz.sync

import tdz.model.ColumnId
import tdz.model.CanvasName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestVvTest {

    @Test fun readsVvByColumnPath() {
        val manifest = """{"name":"_","parentId":null,"columns":"0,11","layouts":"",
            "vv":{"0":{"dev1":2},"11":{"dev2":5}}}"""
        val byPath = ManifestVv.read(manifest, root = "/n", canvas = CanvasName("_"))
        assertEquals(mapOf("dev1" to 2L), byPath["/n/_/col-0.md"])
        assertEquals(mapOf("dev2" to 5L), byPath["/n/_/col-11.md"])
    }

    @Test fun writeAttachesVvKeyedByColumnId() {
        val manifest = """{"name":"_","parentId":null,"columns":"0","layouts":""}"""
        val written = ManifestVv.write(manifest, root = "/n", canvas = CanvasName("_"),
            byPath = mapOf("/n/_/col-0.md" to mapOf("dev1" to 7L)))
        assertEquals(mapOf("dev1" to 7L), ManifestVv.readOne(written, ColumnId("0")))
        // The manifest's canvas fields survive the vv attach.
        assertTrue(written.contains("\"columns\":\"0\""), "canvas fields preserved: $written")
    }

    @Test fun writeAttachesVvForNestedCanvas() {
        // A nested board's column path carries the FULL canvas path (parent/child); write() must
        // match it against the canvas via the root-aware parse, or every vv entry is dropped and
        // concurrent edits to nested boards silently lose to last-write-wins.
        val manifest = """{"name":"child","parentId":null,"columns":"0","layouts":""}"""
        val written = ManifestVv.write(manifest, root = "/n", canvas = CanvasName("parent/child"),
            byPath = mapOf("/n/parent/child/col-0.md" to mapOf("dev1" to 7L)))
        assertEquals(mapOf("dev1" to 7L), ManifestVv.readOne(written, ColumnId("0")))
    }

    @Test fun readMissingVvIsEmptyMap() {
        val manifest = """{"name":"_","parentId":null,"columns":"0","layouts":""}"""
        assertEquals(emptyMap(), ManifestVv.read(manifest, "/n", CanvasName("_")))
    }

    @Test fun readOneReturnsColumnVector() {
        val manifest = """{"name":"_","columns":"0,11","layouts":"","vv":{"0":{"dev1":2}}}"""
        assertEquals(mapOf("dev1" to 2L), ManifestVv.readOne(manifest, ColumnId("0")))
        assertEquals(emptyMap(), ManifestVv.readOne(manifest, ColumnId("11")))   // column present but no vector
        assertEquals(emptyMap(), ManifestVv.readOne(manifest, ColumnId("99")))   // unknown column
    }

    @Test fun readOneMissingVvSectionIsEmpty() {
        val manifest = """{"name":"_","columns":"0","layouts":""}"""
        assertEquals(emptyMap(), ManifestVv.readOne(manifest, ColumnId("0")))
    }

    @Test fun readOneToleratesCorruptManifest() {
        assertEquals(emptyMap(), ManifestVv.readOne("not json at all", ColumnId("0")))
    }

    @Test fun writeAndReadTombstonesRoundTrip() {
        val manifest = """{"name":"_","parentId":null,"columns":"0","layouts":""}"""
        val written = ManifestVv.write(manifest, root = "/n", canvas = CanvasName("_"),
            byPath = emptyMap(), svv = mapOf("dev1" to 3L),
            deleted = mapOf(ColumnId("ab12") to mapOf("dev1" to 2L), ColumnId("cd34") to mapOf("dev2" to 5L)))
        assertEquals(
            mapOf(ColumnId("ab12") to mapOf("dev1" to 2L), ColumnId("cd34") to mapOf("dev2" to 5L)),
            ManifestVv.readDeleted(written))
        assertEquals(mapOf("dev1" to 3L), ManifestVv.readSvv(written), "svv survives alongside tombstones")
    }

    @Test fun readDeletedMissingOrCorruptIsEmpty() {
        // Back-compat: an OLD manifest with no `deleted` field reads as no tombstones (today's behavior).
        assertEquals(emptyMap(), ManifestVv.readDeleted("""{"name":"_","columns":"0","layouts":""}"""))
        assertEquals(emptyMap(), ManifestVv.readDeleted("not json"))
    }

    @Test fun writeOmitsEmptyTombstones() {
        val manifest = """{"name":"_","columns":"0","layouts":""}"""
        val written = ManifestVv.write(manifest, "/n", CanvasName("_"), byPath = emptyMap())
        assertTrue(!written.contains("deleted"), "no empty tombstone section is emitted: $written")
    }
}
