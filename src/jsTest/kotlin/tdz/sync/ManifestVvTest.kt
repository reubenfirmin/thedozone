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
        assertEquals(mapOf("dev1" to 7L), ManifestVv.readOne(written, ColumnId(0)))
        // The manifest's canvas fields survive the vv attach.
        assertTrue(written.contains("\"columns\":\"0\""), "canvas fields preserved: $written")
    }

    @Test fun readMissingVvIsEmptyMap() {
        val manifest = """{"name":"_","parentId":null,"columns":"0","layouts":""}"""
        assertEquals(emptyMap(), ManifestVv.read(manifest, "/n", CanvasName("_")))
    }

    @Test fun readOneReturnsColumnVector() {
        val manifest = """{"name":"_","columns":"0,11","layouts":"","vv":{"0":{"dev1":2}}}"""
        assertEquals(mapOf("dev1" to 2L), ManifestVv.readOne(manifest, ColumnId(0)))
        assertEquals(emptyMap(), ManifestVv.readOne(manifest, ColumnId(11)))   // column present but no vector
        assertEquals(emptyMap(), ManifestVv.readOne(manifest, ColumnId(99)))   // unknown column
    }

    @Test fun readOneMissingVvSectionIsEmpty() {
        val manifest = """{"name":"_","columns":"0","layouts":""}"""
        assertEquals(emptyMap(), ManifestVv.readOne(manifest, ColumnId(0)))
    }

    @Test fun readOneToleratesCorruptManifest() {
        assertEquals(emptyMap(), ManifestVv.readOne("not json at all", ColumnId(0)))
    }
}
