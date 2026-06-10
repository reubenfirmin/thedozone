package tdz.sync

import kotlinx.browser.window
import kotlinx.coroutines.await
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
    val version: String? = null,
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

    // ---- RemoteStorage ----

    override suspend fun list(folder: String): RemoteListing {
        val token = DriveAuth.token()
        val rootId = resolveFolderId(folder, create = false, token)
            ?: return RemoteListing(emptyList(), rootExists = false)
        val out = mutableListOf<RemoteFile>()
        listInto(rootId, normalize(folder), token, out)
        return RemoteListing(out, rootExists = true)
    }

    override suspend fun download(path: String): Pair<String, RemoteFile> {
        val token = DriveAuth.token()
        val id = resolveFileId(path, token) ?: throw Exception("Drive download: not found: $path")
        val content = reqText(token, "GET", "$api/files/$id?alt=media")
        val meta = reqMeta(token, "GET", "$api/files/$id?fields=id,version,modifiedTime")
        return content to toFile(path, meta)
    }

    override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
        val token = DriveAuth.token()
        val parentPath = "/" + DrivePaths.folderSegments(path).joinToString("/")
        val parentId = resolveFolderId(parentPath, create = true, token)
            ?: error("Drive upload: folder '$parentPath' could not be created")
        val name = DrivePaths.fileName(path)
        val existingId = findChild(parentId, name, token)?.id

        if (existingId == null) {
            val meta = createFile(parentId, name, content, token)
            return toFile(path, meta)
        }
        // Optimistic concurrency: compare the known rev to Drive's current `version` before overwriting.
        if (ifMatchRev != null) {
            val current = reqMeta(token, "GET", "$api/files/$existingId?fields=id,version").version
            if (current != ifMatchRev) throw RevConflictException(path)
        }
        val meta = reqMeta(
            token, "PATCH", "$upload/files/$existingId?uploadType=media&fields=id,version,modifiedTime",
            body = content, contentType = "text/plain; charset=UTF-8",
        )
        return toFile(path, meta)
    }

    override suspend fun delete(path: String) {
        val token = DriveAuth.token()
        val id = resolveFileId(path, token) ?: return   // missing = not an error
        val resp = req(token, "DELETE", "$api/files/$id")
        if (!resp.ok && resp.status.toInt() != 404) throw Exception("Drive delete failed: ${resp.status}")
    }

    override suspend fun revoke() = DriveAuth.revoke()

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
        val parentPath = "/" + DrivePaths.folderSegments(path).joinToString("/")
        val parentId = resolveFolderId(parentPath, create = false, token) ?: return null
        return findChild(parentId, DrivePaths.fileName(path), token)?.id
    }

    /** Find a single child by exact name under [parentId]. Returns its metadata or null. */
    private suspend fun findChild(parentId: String, name: String, token: String, foldersOnly: Boolean = false): DriveFileMeta? {
        val folderClause = if (foldersOnly) " and mimeType = '$folderMime'" else ""
        val q = "name = '${escape(name)}' and '$parentId' in parents and trashed = false$folderClause"
        val url = "$api/files?q=${encodeURIComponent(q)}&fields=files(id,name,mimeType,version,modifiedTime)&spaces=drive&pageSize=10"
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
            token, "POST", "$upload/files?uploadType=multipart&fields=id,version,modifiedTime",
            body = multipart, contentType = "multipart/related; boundary=$boundary",
        )
    }

    /** Recursively gather files under [folderId] (whose absolute path is [folderPath]) into [out]. */
    private suspend fun listInto(folderId: String, folderPath: String, token: String, out: MutableList<RemoteFile>) {
        var pageToken: String? = null
        do {
            val pageClause = pageToken?.let { "&pageToken=${encodeURIComponent(it)}" } ?: ""
            val q = "'$folderId' in parents and trashed = false"
            val url = "$api/files?q=${encodeURIComponent(q)}&fields=nextPageToken,files(id,name,mimeType,version,modifiedTime)" +
                "&spaces=drive&pageSize=1000$pageClause"
            val listing = storageJson.decodeFromString<DriveFileList>(reqJson(token, "GET", url))
            for (f in listing.files) {
                val name = f.name ?: continue
                val childPath = DrivePaths.childPath(folderPath, name)
                if (f.mimeType == folderMime) {
                    folderCache[childPath] = f.id
                    listInto(f.id, childPath, token, out)
                } else {
                    out += toFile(childPath, f)
                }
            }
            pageToken = listing.nextPageToken
        } while (pageToken != null)
    }

    private fun toFile(path: String, meta: DriveFileMeta): RemoteFile = RemoteFile(
        path = path,
        rev = meta.version ?: error("Drive metadata for '$path' is missing `version`"),
        serverModified = Date(meta.modifiedTime ?: error("Drive metadata for '$path' is missing `modifiedTime`")).getTime(),
    )

    private fun normalize(folder: String): String = "/" + DrivePaths.segments(folder).joinToString("/")

    // ---- HTTP ----

    private suspend fun req(token: String, method: String, url: String, body: String? = null, contentType: String? = null): Response {
        val headers = Headers()
        headers.append("Authorization", "Bearer $token")
        if (contentType != null) headers.append("Content-Type", contentType)
        return window.fetch(url, RequestInit(method = method, headers = headers, body = body)).await()
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
