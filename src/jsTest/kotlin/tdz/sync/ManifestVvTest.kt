package tdz.sync

import tdz.model.CanvasName
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestVvTest {
    @Test fun readsVvByColumnPath() {
        val manifest = json(
            "name" to "_", "parentId" to null, "columns" to "0,11", "layouts" to "",
            "vv" to json("0" to json("dev1" to 2.0), "11" to json("dev2" to 5.0)),
        )
        val byPath = ManifestVv.read(manifest, root = "/n", canvas = CanvasName("_"))
        assertEquals(mapOf("dev1" to 2L), byPath["/n/_/col-0.md"])
        assertEquals(mapOf("dev2" to 5L), byPath["/n/_/col-11.md"])
    }

    @Test fun writeAttachesVvObjectKeyedByColumnId() {
        val manifest = json("name" to "_", "parentId" to null, "columns" to "0", "layouts" to "")
        ManifestVv.write(manifest, root = "/n", canvas = CanvasName("_"),
            byPath = mapOf("/n/_/col-0.md" to mapOf("dev1" to 7L)))
        val vv = manifest["vv"].asDynamic()
        assertEquals(7.0, vv["0"]["dev1"])
    }

    @Test fun readMissingVvIsEmptyMap() {
        val manifest = json("name" to "_", "parentId" to null, "columns" to "0", "layouts" to "")
        assertEquals(emptyMap(), ManifestVv.read(manifest, "/n", CanvasName("_")))
    }

    @Test fun readOneReturnsColumnVector() {
        val manifest = json("name" to "_", "columns" to "0,11", "layouts" to "",
            "vv" to json("0" to json("dev1" to 2.0)))
        assertEquals(mapOf("dev1" to 2L), ManifestVv.readOne(manifest, 0))
        assertEquals(emptyMap(), ManifestVv.readOne(manifest, 11))   // column present but no vector
        assertEquals(emptyMap(), ManifestVv.readOne(manifest, 99))   // unknown column
    }

    @Test fun readOneMissingVvSectionIsEmpty() {
        val manifest = json("name" to "_", "columns" to "0", "layouts" to "")
        assertEquals(emptyMap(), ManifestVv.readOne(manifest, 0))
    }
}
