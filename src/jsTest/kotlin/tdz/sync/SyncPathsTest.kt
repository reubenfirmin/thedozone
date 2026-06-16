package tdz.sync

import tdz.model.CanvasName
import tdz.model.ColumnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncPathsTest {

    private val canvas = CanvasName("Groceries")

    @Test
    fun columnPathWithRoot() {
        // Case is preserved (normalized() does not lowercase) so paths round-trip to the model key.
        assertEquals("/notes/Groceries/col-2.md", SyncPaths.columnPath("/notes", canvas, ColumnId(2)))
    }

    @Test
    fun columnPathEmptyRoot() {
        assertEquals("/Groceries/col-0.md", SyncPaths.columnPath("", canvas, ColumnId(0)))
    }

    @Test
    fun rootIsNormalizedWithLeadingSlashNoTrailing() {
        assertEquals("/notes/Groceries/col-0.md", SyncPaths.columnPath("notes/", canvas, ColumnId(0)))
    }

    @Test
    fun normalizesUnsafeCharsInCanvasName() {
        // spaces -> dashes, parens stripped (CanvasName.normalized()), case preserved
        assertEquals("/My-List/col-0.md", SyncPaths.columnPath("", CanvasName("My (List)"), ColumnId(0)))
    }

    @Test
    fun manifestPath() {
        assertEquals("/Groceries/_canvas.json", SyncPaths.manifestPath("", canvas))
    }

    @Test
    fun parseColumnRoundTrips() {
        val path = SyncPaths.columnPath("/notes", canvas, ColumnId(7))
        val parsed = SyncPaths.parseColumn(path)
        assertEquals(CanvasName("Groceries"), parsed?.first)
        assertEquals(ColumnId(7), parsed?.second)
    }

    @Test
    fun parseColumnYieldsSameModelKeyAsOriginal() {
        // The invariant the SyncController relies on: parsed canvas normalizes to the same key.
        val original = CanvasName("My Trips")
        val path = SyncPaths.columnPath("/n", original, ColumnId(3))
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

    @Test
    fun manifestPathForReturnsCanvasManifest() {
        assertEquals("/n/_/_canvas.json", SyncPaths.manifestPathFor("/n/_/col-0.md"))
        assertEquals("/_/_canvas.json", SyncPaths.manifestPathFor("/_/col-11.md"))
    }

    @Test
    fun manifestPathForReturnsNullForNonColumn() {
        assertEquals(null, SyncPaths.manifestPathFor("/n/_/_canvas.json"))
        assertEquals(null, SyncPaths.manifestPathFor("/n/_/notacolumn.txt"))
    }

    // ---- Nested board round-trip tests ----

    @Test
    fun parseColumnNestedBoardWithRoot() {
        // Board "a/b/c" under root "notes" → path "/notes/a/b/c/col-5.md"
        // parseColumn MUST return full canvas path "a/b/c", not just "c"
        val nested = CanvasName("a/b/c")
        val path = SyncPaths.columnPath("notes", nested, ColumnId(5))
        assertEquals("/notes/a/b/c/col-5.md", path)
        val parsed = SyncPaths.parseColumn(path, "notes")
        assertEquals("a/b/c", parsed?.first?.normalized())
        assertEquals(ColumnId(5), parsed?.second)
    }

    @Test
    fun parseColumnNestedBoardEmptyRoot() {
        // Board "a/b/c" under empty root → path "/a/b/c/col-0.md"
        // parseColumn MUST return full canvas path "a/b/c", not just "c"
        val nested = CanvasName("a/b/c")
        val path = SyncPaths.columnPath("", nested, ColumnId(0))
        assertEquals("/a/b/c/col-0.md", path)
        val parsed = SyncPaths.parseColumn(path, "")
        assertEquals("a/b/c", parsed?.first?.normalized())
        assertEquals(ColumnId(0), parsed?.second)
    }

    @Test
    fun parseColumnRootLevelBoardWithRoot() {
        // Root-level board "Groceries" under root "notes" still round-trips correctly
        val path = SyncPaths.columnPath("notes", canvas, ColumnId(7))
        val parsed = SyncPaths.parseColumn(path, "notes")
        assertEquals(canvas.normalized(), parsed?.first?.normalized())
        assertEquals(ColumnId(7), parsed?.second)
    }

    @Test
    fun parseColumnRootLevelBoardEmptyRoot() {
        // Root-level board "Groceries" under empty root still round-trips correctly
        val path = SyncPaths.columnPath("", canvas, ColumnId(3))
        val parsed = SyncPaths.parseColumn(path, "")
        assertEquals(canvas.normalized(), parsed?.first?.normalized())
        assertEquals(ColumnId(3), parsed?.second)
    }

    @Test
    fun parseManifestNestedBoardWithRoot() {
        // Board "a/b" under root "notes" → manifest "/notes/a/b/_canvas.json"
        // parseManifest MUST return full canvas path "a/b", not just "b"
        val nested = CanvasName("a/b")
        val path = SyncPaths.manifestPath("notes", nested)
        assertEquals("/notes/a/b/_canvas.json", path)
        val parsed = SyncPaths.parseManifest(path, "notes")
        assertEquals("a/b", parsed?.normalized())
    }

    @Test
    fun parseManifestNestedBoardEmptyRoot() {
        // Board "a/b" under empty root → manifest "/a/b/_canvas.json"
        val nested = CanvasName("a/b")
        val path = SyncPaths.manifestPath("", nested)
        assertEquals("/a/b/_canvas.json", path)
        val parsed = SyncPaths.parseManifest(path, "")
        assertEquals("a/b", parsed?.normalized())
    }

    @Test
    fun parseManifestRootLevelBoard() {
        // Root-level board under root "notes" round-trips correctly
        val path = SyncPaths.manifestPath("notes", canvas)
        val parsed = SyncPaths.parseManifest(path, "notes")
        assertEquals(canvas.normalized(), parsed?.normalized())
    }

    @Test
    fun nestedAndTopLevelBoardsDontCollide() {
        // Nested "a/x" and top-level "x" must map to DIFFERENT folders AND parse back to DIFFERENT canvases
        val nested = CanvasName("a/x")
        val topLevel = CanvasName("x")
        val nestedFolder = SyncPaths.canvasFolder("notes", nested)
        val topFolder = SyncPaths.canvasFolder("notes", topLevel)
        // Different remote folders
        assertEquals("/notes/a/x", nestedFolder)
        assertEquals("/notes/x", topFolder)
        // Each parses back to its own identity
        val nestedParsed = SyncPaths.parseManifest(SyncPaths.manifestPath("notes", nested), "notes")
        val topParsed = SyncPaths.parseManifest(SyncPaths.manifestPath("notes", topLevel), "notes")
        assertEquals("a/x", nestedParsed?.normalized())
        assertEquals("x", topParsed?.normalized())
        // They are distinct
        assertTrue(nestedParsed?.normalized() != topParsed?.normalized())
    }
}
