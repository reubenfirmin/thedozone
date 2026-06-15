package tdz.sync.local

import tdz.sync.RevConflictException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalFolderStorageTest {

    // In-memory fake of the LocalDir/LocalFile seam. Writes bump lastModified deterministically
    // (no Date.now) so rev changes are predictable.
    private class FakeFile(var content: String, var lastModified: Double) : LocalFile {
        override suspend fun read() = content
        override suspend fun write(content: String) { this.content = content; lastModified += 1000.0 }
        override suspend fun meta() = FileMeta(lastModified, content.length.toDouble())
    }
    private class FakeDir : LocalDir {
        val dirs = mutableMapOf<String, FakeDir>()
        val files = mutableMapOf<String, FakeFile>()
        override suspend fun dir(name: String, create: Boolean): LocalDir? =
            dirs[name] ?: if (create) FakeDir().also { dirs[name] = it } else null
        override suspend fun file(name: String, create: Boolean): LocalFile? =
            files[name] ?: if (create) FakeFile("", 1000.0).also { files[name] = it } else null
        override suspend fun entries(): List<Entry> =
            dirs.map { DirEntry(it.key, it.value) } + files.map { FileEntry(it.key, it.value) }
        override suspend fun remove(name: String) { dirs.remove(name); files.remove(name) }
    }

    private fun storage() = LocalFolderStorage(FakeDir())

    @Test fun uploadCreatesNestedFileThenDownloadsIt() = runTest {
        val s = storage()
        val meta = s.upload("/main/col-0.md", "hello", ifMatchRev = null)
        assertEquals("/main/col-0.md", meta.path)
        assertEquals("hello", s.download("/main/col-0.md").first)
    }

    @Test fun revChangesWhenContentChanges() = runTest {
        val s = storage()
        val r1 = s.upload("/main/col-0.md", "a", null).rev
        val r2 = s.upload("/main/col-0.md", "bb", null).rev
        assertNotEquals(r1, r2)
    }

    @Test fun listReturnsOnlySyncFilesWithSlashPrefixedPaths() = runTest {
        val s = storage()
        s.upload("/main/_canvas.json", "{}", null)
        s.upload("/main/col-0.md", "x", null)
        s.upload("/main/notes.txt", "junk", null)   // stray, must be ignored
        val paths = s.list("").files.map { it.path }.toSet()
        assertEquals(setOf("/main/_canvas.json", "/main/col-0.md"), paths)
    }

    @Test fun deleteRemovesFile() = runTest {
        val s = storage()
        s.upload("/main/col-0.md", "x", null)
        s.delete("/main/col-0.md")
        assertTrue(s.list("").files.isEmpty())
    }

    @Test fun deleteMissingIsNoError() = runTest {
        storage().delete("/nope/col-0.md")   // must not throw
    }

    @Test fun staleIfMatchRevThrowsConflict() = runTest {
        val s = storage()
        val rev = s.upload("/main/col-0.md", "a", null).rev
        s.upload("/main/col-0.md", "b", null)   // rev moves out from under us
        assertFailsWith<RevConflictException> { s.upload("/main/col-0.md", "c", ifMatchRev = rev) }
    }

    @Test fun matchingIfMatchRevSucceeds() = runTest {
        val s = storage()
        val rev = s.upload("/main/col-0.md", "a", null).rev
        s.upload("/main/col-0.md", "b", ifMatchRev = rev)
        assertEquals("b", s.download("/main/col-0.md").first)
    }

    @Test fun ifMatchRevOnNewFileSucceeds() = runTest {
        val s = storage()
        s.upload("/main/col-0.md", "a", ifMatchRev = "anything")   // no existing file -> no conflict
        assertEquals("a", s.download("/main/col-0.md").first)
    }
}
