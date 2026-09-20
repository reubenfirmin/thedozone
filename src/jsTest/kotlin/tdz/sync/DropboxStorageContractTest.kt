package tdz.sync

import kotlin.js.Date
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contract test pinning how [dropboxEntryToFile] maps the untyped Dropbox FileMetadata response to a
 * typed [RemoteFile]. Response-shape drift here is exactly what silently broke sync (the temp-upload
 * link returns a content-hash-only body, NOT metadata) and could only be caught by a live Dropbox
 * round-trip otherwise. These fixtures lock the field mapping so an SDK/endpoint change regresses a
 * test instead of production.
 */
class DropboxStorageContractTest {

    @Test
    fun mapsAFileMetadataResponse() {
        // The FileMetadata shape returned by files/list_folder and files/get_metadata.
        val meta = json(
            ".tag" to "file",
            "name" to "Board.tdz",
            "path_lower" to "/sync/board.tdz",
            "path_display" to "/Sync/Board.tdz",
            "rev" to "0123456789abcdef",
            "server_modified" to "2024-01-02T03:04:05Z",
            "size" to 42,
        )
        val file = dropboxEntryToFile(meta)
        assertEquals("/Sync/Board.tdz", file.path, "uses path_display (original case), not path_lower")
        assertEquals("0123456789abcdef", file.rev)
        assertEquals(Date("2024-01-02T03:04:05Z").getTime(), file.serverModified, "server_modified parsed as a date")
    }

    @Test
    fun rejectsContentHashOnlyUploadBody() {
        // The temp-upload-link POST returns ONLY this — not metadata. It must NOT be coerced into a
        // RemoteFile; the upload path fetches the real metadata via files/get_metadata instead.
        val body = json("content-hash" to "a700f28dd43da3e155d26001529f635a95d2e5deed678db324c849e81c64dd06")
        val e = assertFailsWith<IllegalStateException> { dropboxEntryToFile(body) }
        assertTrue((e.message ?: "").contains("path_display"), "fails loudly on the missing field")
    }

    @Test
    fun rejectsMissingRev() {
        val meta = json("path_display" to "/x.tdz", "server_modified" to "2024-01-02T03:04:05Z")
        assertFailsWith<IllegalStateException> { dropboxEntryToFile(meta) }
    }

    @Test
    fun rejectsMissingServerModified() {
        val meta = json("path_display" to "/x.tdz", "rev" to "abc")
        assertFailsWith<IllegalStateException> { dropboxEntryToFile(meta) }
    }
}
