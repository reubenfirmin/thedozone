package tdz.sync

import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.js.Date
import kotlin.js.Promise

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
        val meta = reqJson(token, "GET", "$api/files/$id?fields=id,version,modifiedTime")
        return content to toFile(path, meta)
    }

    override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
        val token = DriveAuth.token()
        val parentPath = "/" + DrivePaths.folderSegments(path).joinToString("/")
        val parentId = resolveFolderId(parentPath, create = true, token)!!
        val name = DrivePaths.fileName(path)
        val existing = findChild(parentId, name, token)
        val existingId: String? = if (existing != null && existing != undefined) existing.id as String else null

        if (existingId == null) {
            val meta = createFile(parentId, name, content, token)
            return toFile(path, meta)
        }
        // Optimistic concurrency: compare the known rev to Drive's current `version` before overwriting.
        if (ifMatchRev != null) {
            val current = reqJson(token, "GET", "$api/files/$existingId?fields=version").version as String
            if (current != ifMatchRev) throw RevConflictException(path)
        }
        val meta = reqJson(
            token, "PATCH", "$upload/files/$existingId?uploadType=media&fields=id,version,modifiedTime",
            body = content, contentType = "text/plain; charset=UTF-8",
        )
        return toFile(path, meta)
    }

    override suspend fun delete(path: String) {
        val token = DriveAuth.token()
        val id = resolveFileId(path, token) ?: return   // missing = not an error
        val resp = req(token, "DELETE", "$api/files/$id")
        if (resp.ok != true && resp.status != 404) throw Exception("Drive delete failed: ${resp.status}")
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
                val id = (found?.id as String?) ?: if (create) createFolder(parentId, seg, token) else return@withLock null
                folderCache[acc] = id
                parentId = id
            }
            parentId
        }

    private suspend fun resolveFileId(path: String, token: String): String? {
        val parentPath = "/" + DrivePaths.folderSegments(path).joinToString("/")
        val parentId = resolveFolderId(parentPath, create = false, token) ?: return null
        return findChild(parentId, DrivePaths.fileName(path), token)?.id as String?
    }

    /** Find a single child by exact name under [parentId]. Returns its metadata or null. */
    private suspend fun findChild(parentId: String, name: String, token: String, foldersOnly: Boolean = false): dynamic {
        val folderClause = if (foldersOnly) " and mimeType = '$folderMime'" else ""
        val q = "name = '${escape(name)}' and '$parentId' in parents and trashed = false$folderClause"
        val url = "$api/files?q=${encode(q)}&fields=files(id,name,mimeType,version,modifiedTime)&spaces=drive&pageSize=10"
        val files = reqJson(token, "GET", url).files as Array<dynamic>
        return files.firstOrNull()
    }

    private suspend fun createFolder(parentId: String, name: String, token: String): String {
        val obj: dynamic = js("({})")
        obj.name = name
        obj.mimeType = folderMime
        obj.parents = arrayOf(parentId)
        return reqJson(token, "POST", "$api/files?fields=id", JSON.stringify(obj), "application/json").id as String
    }

    private suspend fun createFile(parentId: String, name: String, content: String, token: String): dynamic {
        val boundary = "tdz-${Date.now().toString().replace(".", "")}-boundary"
        val metaObj: dynamic = js("({})")
        metaObj.name = name
        metaObj.parents = arrayOf(parentId)
        val meta = JSON.stringify(metaObj)
        val multipart = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(meta).append("\r\n")
            append("--$boundary\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
            append(content).append("\r\n")
            append("--$boundary--")
        }
        return reqJson(
            token, "POST", "$upload/files?uploadType=multipart&fields=id,version,modifiedTime",
            body = multipart, contentType = "multipart/related; boundary=$boundary",
        )
    }

    /** Recursively gather files under [folderId] (whose absolute path is [folderPath]) into [out]. */
    private suspend fun listInto(folderId: String, folderPath: String, token: String, out: MutableList<RemoteFile>) {
        var pageToken: String? = null
        do {
            val pageClause = pageToken?.let { "&pageToken=${encode(it)}" } ?: ""
            val q = "'$folderId' in parents and trashed = false"
            val url = "$api/files?q=${encode(q)}&fields=nextPageToken,files(id,name,mimeType,version,modifiedTime)" +
                "&spaces=drive&pageSize=1000$pageClause"
            val resp = reqJson(token, "GET", url)
            val files = resp.files as Array<dynamic>
            for (f in files) {
                val name = f.name as String
                val childPath = DrivePaths.childPath(folderPath, name)
                if (f.mimeType == folderMime) {
                    folderCache[childPath] = f.id as String
                    listInto(f.id as String, childPath, token, out)
                } else {
                    out += toFile(childPath, f)
                }
            }
            pageToken = resp.nextPageToken as String?
        } while (pageToken != null)
    }

    private fun toFile(path: String, meta: dynamic): RemoteFile = RemoteFile(
        path = path,
        rev = meta.version.toString(),
        serverModified = Date(meta.modifiedTime as String).getTime(),
    )

    private fun normalize(folder: String): String = "/" + DrivePaths.segments(folder).joinToString("/")

    // ---- HTTP ----

    private suspend fun req(token: String, method: String, url: String, body: String? = null, contentType: String? = null): dynamic {
        val init = js("({})")
        init.method = method
        val headers = js("({})")
        headers["Authorization"] = "Bearer $token"
        if (contentType != null) headers["Content-Type"] = contentType
        init.headers = headers
        if (body != null) init.body = body
        return (kotlinx.browser.window.asDynamic().fetch(url, init) as Promise<dynamic>).await()
    }

    private suspend fun reqJson(token: String, method: String, url: String, body: String? = null, contentType: String? = null): dynamic {
        val resp = req(token, method, url, body, contentType)
        if (resp.ok != true) throw Exception("Drive ${method} ${pathOf(url)} failed: ${resp.status}")
        return (resp.json() as Promise<dynamic>).await()
    }

    private suspend fun reqText(token: String, method: String, url: String): String {
        val resp = req(token, method, url)
        if (resp.ok != true) throw Exception("Drive $method ${pathOf(url)} failed: ${resp.status}")
        return (resp.text() as Promise<String>).await()
    }

    private fun pathOf(url: String): String = url.substringBefore('?').substringAfter(".com")

    private fun escape(name: String): String = name.replace("\\", "\\\\").replace("'", "\\'")

    private fun encode(s: String): String = js("encodeURIComponent")(s) as String
}
