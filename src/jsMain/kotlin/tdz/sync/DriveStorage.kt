package tdz.sync

import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import tdz.model.localstorage.storageJson
import zoned.framework.interop.encodeURIComponent
import kotlin.js.Date

// ---- Drive REST wire types (decoded with kotlinx.serialization; unknown fields ignored) ----
// NOTE: plain `fetch` from org.w3c.fetch is used deliberately: it is fully typed (Headers/
// RequestInit/Response), and this file touches no DOM types, so the org.w3c-vs-web.* split
// (which matters when ELEMENT types cross) doesn't arise. The web.http equivalents work too
// (their constants/suspend helpers are top-level EXTENSIONS — import web.http.GET / web.http.text)
// but offer nothing extra here.

@Serializable
internal data class DriveFileMeta(
    val id: String,
    val name: String? = null,
    val mimeType: String? = null,
    // The sync token (RemoteFile.rev) is the content MD5, NOT Drive's `version`: `version` bumps
    // more than once per write (media + metadata touches), so the rev recorded from an upload
    // response never matched the next listing's version — every file looked changed every sync.
    // md5Checksum is a pure function of the bytes, identical in the upload response and the next
    // listing, so an unchanged file's token is stable and the pull skips it without a download.
    val md5Checksum: String? = null,
    val modifiedTime: String? = null,
)

@Serializable
private data class DriveFileList(
    val files: List<DriveFileMeta> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
private data class DriveCreateRequest(
    val name: String,
    val parents: List<String>,
    val mimeType: String? = null,
)

/**
 * [RemoteStorage] backed by the Google Drive v3 REST API (plain `fetch`; no SDK). Access tokens come
 * from [DriveAuth] (minted/refreshed silently). Drive is ID-based with no real paths, so this maps the
 * absolute [SyncPaths] strings onto Drive by walking a folder chain by name (find-or-create), caching
 * `folderPath -> id`. Scope is `drive.file`, so only files this app created are visible.
 *
 * Concurrency: [SyncEngine] runs file ops in parallel. JS is single-threaded, but suspends interleave,
 * so a [Mutex] guards folder resolution to prevent two concurrent uploads from each creating the same
 * canvas folder. After the first resolve, folders are cache hits (no network) inside the lock.
 */
class DriveStorage : RemoteStorage {

    private val api = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3"
    private val folderMime = "application/vnd.google-apps.folder"

    private val folderCache = mutableMapOf<String, String>()   // absolute folder path -> Drive id
    private val folderMutex = Mutex()

    // File id/metadata seen most recently (filled by list/lookups/uploads). Drive ops used to be
    // 3 round trips each — name-lookup query, content, metadata — even though every sync STARTS
    // with a listing that already returned id+md5Checksum+modifiedTime for every file. With these,
    // a listed file's download is ONE request. Staleness is bounded by the surrounding sync's
    // optimistic concurrency (a stale rev surfaces as RevConflict; a stale id as a 404, which
    // invalidates and re-resolves).
    private val fileIdCache = mutableMapOf<String, String>()         // absolute file path -> Drive id
    private val fileMetaCache = mutableMapOf<String, DriveFileMeta>() // absolute file path -> last seen meta

    // Content cache keyed by rev: a file's body for a given version never changes, so repeated reads
    // collapse to ONE fetch. The win that motivated this: columnVv reads a canvas manifest to find a
    // column's version vector, so an N-column canvas used to download its manifest N times. In-flight
    // dedup means the parallel pull's concurrent readers of the same file (every column of a canvas
    // hitting columnVv at once) share a single request instead of each issuing their own.
    private val contentCache = mutableMapOf<String, Pair<String, String>>()        // path -> (rev, content)
    private val contentInFlight = mutableMapOf<String, CompletableDeferred<String>>()
    private val contentLock = Mutex()

    private fun cacheFile(path: String, meta: DriveFileMeta) {
        fileIdCache[path] = meta.id
        fileMetaCache[path] = meta
    }

    private fun invalidateFile(path: String) {
        fileIdCache.remove(path)
        fileMetaCache.remove(path)
        contentCache.remove(path)
    }

    private fun isNotFound(t: Throwable) = t.message?.contains(" 404") == true

    /** Fetch a file's body, serving a rev-matched cache hit and sharing concurrent identical fetches. */
    private suspend fun fetchContent(path: String, id: String, rev: String, token: String): String {
        val (deferred, owner) = contentLock.withLock {
            contentCache[path]?.let { (r, c) -> if (r == rev) return c }   // non-local: cache hit
            contentInFlight[path]?.let { return@withLock it to false }     // join an in-flight fetch
            CompletableDeferred<String>().also { contentInFlight[path] = it } to true
        }
        if (!owner) return deferred.await()
        return try {
            val content = reqText(token, "GET", "$api/files/$id?alt=media")
            contentLock.withLock { contentCache[path] = rev to content; contentInFlight.remove(path) }
            deferred.complete(content)
            content
        } catch (t: Throwable) {
            contentLock.withLock { contentInFlight.remove(path) }
            deferred.completeExceptionally(t)
            throw t
        }
    }

    // ---- RemoteStorage ----

    override suspend fun list(folder: String): RemoteListing {
        val token = DriveAuth.token()
        val rootId = resolveFolderId(folder, create = false, token)
            ?: return RemoteListing(emptyList(), rootExists = false)
        return RemoteListing(listFolder(rootId, normalize(folder), token), rootExists = true)
    }

    override suspend fun download(path: String): Pair<String, RemoteFile> {
        val token = DriveAuth.token()
        // Fast path: id AND metadata already known (from this sync's listing) -> at most one request,
        // and zero when fetchContent serves a cached/in-flight body.
        val cachedMeta = fileMetaCache[path]
        fileIdCache[path]?.let { id ->
            if (cachedMeta?.md5Checksum != null) {
                try {
                    return fetchContent(path, id, cachedMeta.md5Checksum!!, token) to toFile(path, cachedMeta)
                } catch (t: Throwable) {
                    if (!isNotFound(t)) throw t
                    invalidateFile(path)   // stale id (deleted/recreated elsewhere): re-resolve below
                }
            }
        }
        val id = resolveFileId(path, token) ?: throw Exception("Drive download: not found: $path")
        val meta = reqMeta(token, "GET", "$api/files/$id?fields=id,md5Checksum,modifiedTime")
        cacheFile(path, meta)
        val content = fetchContent(path, id, meta.md5Checksum ?: error("Drive metadata for '$path' missing md5Checksum"), token)
        return content to toFile(path, meta)
    }

    override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile =
        uploadAttempt(path, content, ifMatchRev, retryOnStaleId = true)

    private suspend fun uploadAttempt(path: String, content: String, ifMatchRev: String?, retryOnStaleId: Boolean): RemoteFile {
        val token = DriveAuth.token()
        val parentPath = "/" + DrivePaths.folderSegments(path).joinToString("/")
        val parentId = resolveFolderId(parentPath, create = true, token)
            ?: error("Drive upload: folder '$parentPath' could not be created")
        val name = DrivePaths.fileName(path)
        val cachedId = fileIdCache[path]
        try {
            val existingId = cachedId ?: findChild(parentId, name, token)?.id

            if (existingId == null) {
                val meta = createFile(parentId, name, content, token)
                cacheFile(path, meta)
                return toFile(path, meta)
            }
            // Optimistic concurrency: compare the known rev (content MD5) to the remote's current
            // checksum before overwriting. The listing already fetched every file's md5 this
            // reconcile, so when that snapshot matches our expected rev we skip the pre-check GET
            // (the common no-conflict case — one saved round trip per file). Only an unknown or
            // DIFFERING snapshot pays for a fresh check, so a real conflict is never silently
            // overwritten.
            if (ifMatchRev != null) {
                val snapshotRev = fileMetaCache[path]?.md5Checksum
                if (snapshotRev != ifMatchRev) {
                    val current = reqMeta(token, "GET", "$api/files/$existingId?fields=id,md5Checksum").md5Checksum
                    if (current != ifMatchRev) throw RevConflictException(path)
                }
            }
            val meta = reqMeta(
                token, "PATCH", "$upload/files/$existingId?uploadType=media&fields=id,md5Checksum,modifiedTime",
                body = content, contentType = "text/plain; charset=UTF-8",
            )
            cacheFile(path, meta)
            return toFile(path, meta)
        } catch (t: Throwable) {
            // A cached id can go stale (file deleted/recreated elsewhere): invalidate + retry once.
            if (retryOnStaleId && cachedId != null && isNotFound(t)) {
                invalidateFile(path)
                return uploadAttempt(path, content, ifMatchRev, retryOnStaleId = false)
            }
            throw t
        }
    }

    override suspend fun delete(path: String) {
        val token = DriveAuth.token()
        val id = resolveFileId(path, token) ?: return   // missing = not an error
        val resp = req(token, "DELETE", "$api/files/$id")
        invalidateFile(path)
        if (!resp.ok && resp.status.toInt() != 404) throw Exception("Drive delete failed: ${resp.status}")
    }

    override suspend fun revoke() = DriveAuth.revoke()

    // Drive has no refresh token: ready only when a live access token is already cached. A miss must
    // NOT trigger a renewal popup from the automatic reconcile — Reconnect (a user gesture) does that.
    override fun isReady(): Boolean = DriveAuth.hasUsableToken()

    // ---- path <-> id resolution ----

    /** Resolve a folder path to a Drive id, optionally creating missing segments. Serialized via mutex. */
    private suspend fun resolveFolderId(folder: String, create: Boolean, token: String): String? =
        folderMutex.withLock {
            var parentId = "root"
            var acc = ""
            for (seg in DrivePaths.segments(folder)) {
                acc += "/$seg"
                val cached = folderCache[acc]
                if (cached != null) { parentId = cached; continue }
                val found = findChild(parentId, seg, token, foldersOnly = true)
                val id = found?.id ?: if (create) createFolder(parentId, seg, token) else return@withLock null
                folderCache[acc] = id
                parentId = id
            }
            parentId
        }

    private suspend fun resolveFileId(path: String, token: String): String? {
        fileIdCache[path]?.let { return it }
        val parentPath = "/" + DrivePaths.folderSegments(path).joinToString("/")
        val parentId = resolveFolderId(parentPath, create = false, token) ?: return null
        val found = findChild(parentId, DrivePaths.fileName(path), token) ?: return null
        cacheFile(path, found)
        return found.id
    }

    /** Find a single child by exact name under [parentId]. Returns its metadata or null. */
    private suspend fun findChild(parentId: String, name: String, token: String, foldersOnly: Boolean = false): DriveFileMeta? {
        val folderClause = if (foldersOnly) " and mimeType = '$folderMime'" else ""
        val q = "name = '${escape(name)}' and '$parentId' in parents and trashed = false$folderClause"
        val url = "$api/files?q=${encodeURIComponent(q)}&fields=files(id,name,mimeType,md5Checksum,modifiedTime)&spaces=drive&pageSize=10"
        return storageJson.decodeFromString<DriveFileList>(reqJson(token, "GET", url)).files.firstOrNull()
    }

    private suspend fun createFolder(parentId: String, name: String, token: String): String {
        val body = storageJson.encodeToString(DriveCreateRequest(name, listOf(parentId), folderMime))
        val resp = reqJson(token, "POST", "$api/files?fields=id", body, "application/json")
        return storageJson.decodeFromString<DriveFileMeta>(resp).id
    }

    private suspend fun createFile(parentId: String, name: String, content: String, token: String): DriveFileMeta {
        val boundary = "tdz-${Date.now().toString().replace(".", "")}-boundary"
        val meta = storageJson.encodeToString(DriveCreateRequest(name, listOf(parentId)))
        val multipart = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(meta).append("\r\n")
            append("--$boundary\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
            append(content).append("\r\n")
            append("--$boundary--")
        }
        return reqMeta(
            token, "POST", "$upload/files?uploadType=multipart&fields=id,md5Checksum,modifiedTime",
            body = multipart, contentType = "multipart/related; boundary=$boundary",
        )
    }

    /**
     * Recursively gather files under [folderId] (absolute path [folderPath]). Sibling subfolders are
     * listed CONCURRENTLY: a folder-per-canvas tree used to be walked one canvas at a time (the bulk
     * of a sync's `list` cost), so the depth-N walk now costs ~N round-trip waves, not one per folder.
     */
    private suspend fun listFolder(folderId: String, folderPath: String, token: String): List<RemoteFile> = coroutineScope {
        val files = mutableListOf<RemoteFile>()
        val subfolders = mutableListOf<Pair<String, String>>()   // (id, childPath)
        var pageToken: String? = null
        do {
            val pageClause = pageToken?.let { "&pageToken=${encodeURIComponent(it)}" } ?: ""
            val q = "'$folderId' in parents and trashed = false"
            val url = "$api/files?q=${encodeURIComponent(q)}&fields=nextPageToken,files(id,name,mimeType,md5Checksum,modifiedTime)" +
                "&spaces=drive&pageSize=1000$pageClause"
            val listing = storageJson.decodeFromString<DriveFileList>(reqJson(token, "GET", url))
            for (f in listing.files) {
                val name = f.name ?: continue
                val childPath = DrivePaths.childPath(folderPath, name)
                if (f.mimeType == folderMime) {
                    folderCache[childPath] = f.id
                    subfolders += f.id to childPath
                } else {
                    cacheFile(childPath, f)   // the listing IS the id+meta lookup for this sync
                    files += toFile(childPath, f)
                }
            }
            pageToken = listing.nextPageToken
        } while (pageToken != null)
        val nested = subfolders.map { (id, path) -> async { listFolder(id, path, token) } }.awaitAll()
        files + nested.flatten()
    }

    private fun toFile(path: String, meta: DriveFileMeta): RemoteFile = RemoteFile(
        path = path,
        rev = meta.md5Checksum ?: error("Drive metadata for '$path' is missing `md5Checksum`"),
        serverModified = Date(meta.modifiedTime ?: error("Drive metadata for '$path' is missing `modifiedTime`")).getTime(),
    )

    private fun normalize(folder: String): String = "/" + DrivePaths.segments(folder).joinToString("/")

    // ---- HTTP ----

    private suspend fun req(token: String, method: String, url: String, body: String? = null, contentType: String? = null): Response {
        val headers = Headers()
        headers.append("Authorization", "Bearer $token")
        if (contentType != null) headers.append("Content-Type", contentType)
        // Per-request timing: a Drive storage op is MANY round trips (folder resolution, name
        // lookup, content, metadata) — the `http` tally in SyncLog.summary() is what shows it.
        val t0 = Date.now()
        val resp = window.fetch(url, RequestInit(method = method, headers = headers, body = body)).await()
        SyncLog.op("http", "$method ${pathOf(url)} → ${resp.status}", Date.now() - t0)
        return resp
    }

    /** Request whose response body is JSON; returned raw for kotlinx.serialization decoding. */
    private suspend fun reqJson(token: String, method: String, url: String, body: String? = null, contentType: String? = null): String {
        val resp = req(token, method, url, body, contentType)
        if (!resp.ok) throw Exception("Drive $method ${pathOf(url)} failed: ${resp.status}")
        return resp.text().await()
    }

    private suspend fun reqMeta(token: String, method: String, url: String, body: String? = null, contentType: String? = null): DriveFileMeta =
        storageJson.decodeFromString(reqJson(token, method, url, body, contentType))

    private suspend fun reqText(token: String, method: String, url: String): String {
        val resp = req(token, method, url)
        if (!resp.ok) throw Exception("Drive $method ${pathOf(url)} failed: ${resp.status}")
        return resp.text().await()
    }

    private fun pathOf(url: String): String = url.substringBefore('?').substringAfter(".com")

    private fun escape(name: String): String = name.replace("\\", "\\\\").replace("'", "\\'")
}
