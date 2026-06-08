package tdz.sync

import tdz.model.CanvasName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncPathsTest {

    private val canvas = CanvasName("Groceries")

    @Test
    fun columnPathWithRoot() {
        // Case is preserved (normalized() does not lowercase) so paths round-trip to the model key.
        assertEquals("/notes/Groceries/col-2.md", SyncPaths.columnPath("/notes", canvas, 2))
    }

    @Test
    fun columnPathEmptyRoot() {
        assertEquals("/Groceries/col-0.md", SyncPaths.columnPath("", canvas, 0))
    }

    @Test
    fun rootIsNormalizedWithLeadingSlashNoTrailing() {
        assertEquals("/notes/Groceries/col-0.md", SyncPaths.columnPath("notes/", canvas, 0))
    }

    @Test
    fun normalizesUnsafeCharsInCanvasName() {
        // spaces -> dashes, parens stripped (CanvasName.normalized()), case preserved
        assertEquals("/My-List/col-0.md", SyncPaths.columnPath("", CanvasName("My (List)"), 0))
    }

    @Test
    fun manifestPath() {
        assertEquals("/Groceries/_canvas.json", SyncPaths.manifestPath("", canvas))
    }

    @Test
    fun parseColumnRoundTrips() {
        val path = SyncPaths.columnPath("/notes", canvas, 7)
        val parsed = SyncPaths.parseColumn(path)
        assertEquals(CanvasName("Groceries"), parsed?.first)
        assertEquals(7, parsed?.second)
    }

    @Test
    fun parseColumnYieldsSameModelKeyAsOriginal() {
        // The invariant the SyncController relies on: parsed canvas normalizes to the same key.
        val original = CanvasName("My Trips")
        val path = SyncPaths.columnPath("/n", original, 3)
        val parsed = SyncPaths.parseColumn(path)!!.first
        assertEquals(original.normalized(), parsed.normalized())
    }

    @Test
    fun parseColumnRejectsManifestAndOther() {
        assertNull(SyncPaths.parseColumn("/groceries/_canvas.json"))
        assertNull(SyncPaths.parseColumn("/groceries/notes.txt"))
    }

    @Test
    fun rootPrefixIsAValidDropboxPath() {
        // list_folder rejects a non-empty path without a leading slash; "" means the app-folder root.
        assertEquals("", SyncPaths.rootPrefix(""))
        assertEquals("", SyncPaths.rootPrefix("/"))
        assertEquals("", SyncPaths.rootPrefix("  "))
        assertEquals("/notes", SyncPaths.rootPrefix("notes"))
        assertEquals("/notes", SyncPaths.rootPrefix("/notes/"))
        assertEquals("/a/b", SyncPaths.rootPrefix("a/b"))
    }

    @Test
    fun isManifestDetectsManifest() {
        assertTrue(SyncPaths.isManifest("/groceries/_canvas.json"))
        assertTrue(!SyncPaths.isManifest("/groceries/col-0.md"))
    }
}
