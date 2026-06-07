package tdz.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class DrivePathsTest {

    @Test
    fun segmentsDropsEmpties() {
        assertEquals(listOf("base", "Canvas", "col-1.md"), DrivePaths.segments("/base/Canvas/col-1.md"))
        assertEquals(emptyList(), DrivePaths.segments(""))
        assertEquals(emptyList(), DrivePaths.segments("/"))
        assertEquals(listOf("a"), DrivePaths.segments("/a"))
    }

    @Test
    fun folderSegmentsExcludeFilename() {
        assertEquals(listOf("base", "Canvas"), DrivePaths.folderSegments("/base/Canvas/col-1.md"))
        assertEquals(emptyList(), DrivePaths.folderSegments("/file.md"))
        assertEquals(emptyList(), DrivePaths.folderSegments(""))
    }

    @Test
    fun fileNameIsLastSegment() {
        assertEquals("col-1.md", DrivePaths.fileName("/base/Canvas/col-1.md"))
        assertEquals("_canvas.json", DrivePaths.fileName("/base/Canvas/_canvas.json"))
        assertEquals("", DrivePaths.fileName(""))
    }

    @Test
    fun childPathJoins() {
        assertEquals("/base/Canvas/col-1.md", DrivePaths.childPath("/base/Canvas", "col-1.md"))
        assertEquals("/base/Canvas", DrivePaths.childPath("/base", "Canvas"))
        assertEquals("/Canvas", DrivePaths.childPath("", "Canvas"))
        assertEquals("/Canvas", DrivePaths.childPath("/", "Canvas"))
    }

    @Test
    fun childPathRoundTripsWithSyncPaths() {
        // Reconstructing a path during list descent must equal what SyncPaths produced.
        val folder = "/base/Canvas"
        val full = DrivePaths.childPath(folder, "col-3.md")
        assertEquals(folder, "/" + DrivePaths.folderSegments(full).joinToString("/"))
        assertEquals("col-3.md", DrivePaths.fileName(full))
    }
}
