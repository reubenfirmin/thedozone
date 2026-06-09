package tdz.sync

import kotlin.js.Json

/** Metadata for a remote file. [serverModified] is epoch millis. */
data class RemoteFile(val path: String, val rev: String, val serverModified: Double)

/** Raised by [RemoteStorage.upload] when the optimistic `ifMatchRev` no longer matches remote. */
class RevConflictException(val path: String) : Exception("rev conflict: $path")

/**
 * Result of [RemoteStorage.list]. [rootExists] is false when the sync root folder could not be found
 * at all (fresh connect, unresolved/typo'd folder, or a provider that has never seen this app). An
 * empty [files] with [rootExists] == false must NEVER be read as "all remote files were deleted" —
 * that is the data-loss bug ([SyncEngine.pull] only propagates deletions when the root is confirmed).
 */
data class RemoteListing(val files: List<RemoteFile>, val rootExists: Boolean)

/**
 * The storage seam. The real implementation ([DropboxStorage]) wraps the SDK; tests use a fake.
 * All paths are absolute Dropbox paths (see [SyncPaths]). Suspend functions run on coroutines.
 */
interface RemoteStorage {
    /** Recursively list files under [folder] ("" or "/" = root); also reports whether the root exists. */
    suspend fun list(folder: String): RemoteListing

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

    /**
     * The version vector recorded for the column at [path], read from its canvas manifest's `vv`
     * section. Empty if the manifest or vector is absent. Default impl uses [download]; backends need
     * not override it (the test fake does, to serve in-memory vectors).
     */
    suspend fun columnVv(path: String): VersionVector {
        val manifestPath = SyncPaths.manifestPathFor(path) ?: return emptyMap()
        val colId = SyncPaths.parseColumn(path)?.second ?: return emptyMap()
        return runCatching {
            val (content, _) = download(manifestPath)
            ManifestVv.readOne(JSON.parse<Json>(content), colId)
        }.getOrDefault(emptyMap())
    }
}
