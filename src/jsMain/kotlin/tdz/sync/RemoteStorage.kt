package tdz.sync

/** Metadata for a remote file. [serverModified] is epoch millis. */
data class RemoteFile(val path: String, val rev: String, val serverModified: Double)

/** Raised by [RemoteStorage.upload] when the optimistic `ifMatchRev` no longer matches remote. */
class RevConflictException(val path: String) : Exception("rev conflict: $path")

/**
 * The storage seam. The real implementation ([DropboxStorage]) wraps the SDK; tests use a fake.
 * All paths are absolute Dropbox paths (see [SyncPaths]). Suspend functions run on coroutines.
 */
interface RemoteStorage {
    /** Recursively list files under [folder] ("" or "/" = root). Folders themselves are omitted. */
    suspend fun list(folder: String): List<RemoteFile>

    /** Download [path]; returns its text content and metadata. */
    suspend fun download(path: String): Pair<String, RemoteFile>

    /**
     * Upload [content] to [path]. When [ifMatchRev] is non-null, use optimistic concurrency and
     * throw [RevConflictException] if the remote rev moved. When null, overwrite unconditionally.
     */
    suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile

    /** Delete [path]; a missing file is not an error. */
    suspend fun delete(path: String)

    /** Revoke the current token, de-authorizing the app on the provider (best-effort). */
    suspend fun revoke()
}
