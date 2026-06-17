package tdz.sync

import kotlinx.coroutines.await
import web.blob.Blob
import kotlin.js.Date
import kotlin.js.json

/**
 * [RemoteStorage] backed by the Dropbox SDK. Constructed with the stored [refreshToken]; the public
 * app key comes from the baked-in [DropboxApp.KEY]. The SDK mints/refreshes short-lived access tokens
 * automatically. All `dynamic` SDK responses are mapped to typed [RemoteFile]s here.
 */
class DropboxStorage(refreshToken: String) : RemoteStorage {

    private val dbx = DropboxSdk(
        json("clientId" to DropboxApp.KEY, "refreshToken" to refreshToken)
    )

    private fun toMillis(serverModified: String): Double = Date(serverModified).getTime()

    // Use path_display (original case), NOT path_lower — SyncPaths preserves canvas-name case so
    // pulled paths must round-trip to the same model key. (path_lower would break that.)
    private fun entryToFile(e: dynamic): RemoteFile = RemoteFile(
        // Validate the untyped SDK response rather than blind-casting: a missing/renamed field
        // should fail loudly here, not surface a null-as-String corruption deep in sync.
        path = e.path_display as? String ?: error("Dropbox entry missing path_display"),
        rev = e.rev as? String ?: error("Dropbox entry missing rev"),
        serverModified = toMillis(e.server_modified as? String ?: error("Dropbox entry missing server_modified")),
    )

    override suspend fun list(folder: String): RemoteListing {
        // Dropbox uses "" for the root, not "/".
        val arg = json("path" to (if (folder == "/") "" else folder), "recursive" to true)
        val files = mutableListOf<RemoteFile>()
        var resp = try {
            dbx.filesListFolder(arg).await()
        } catch (e: dynamic) {
            // Only a genuinely-missing folder (first sync) means "no remote files". Any OTHER error
            // (network/auth/rate-limit) MUST propagate: returning empty here would make pull() treat
            // every tracked path as remotely-deleted and wipe local columns. Let reconcile fail instead.
            if (isNotFound(e)) return RemoteListing(emptyList(), rootExists = false)
            throw Exception("Dropbox list failed: ${describe(e)}")
        }
        while (true) {
            val entries = resp.result.entries as Array<dynamic>
            entries.forEach { if (it[".tag"] == "file") files += entryToFile(it) }
            if (resp.result.has_more as Boolean) {
                resp = try {
                    dbx.filesListFolderContinue(json("cursor" to resp.result.cursor)).await()
                } catch (e: dynamic) {
                    throw Exception("Dropbox list failed (continuation): ${describe(e)}")
                }
            } else break
        }
        return RemoteListing(files, rootExists = true)
    }

    override suspend fun download(path: String): Pair<String, RemoteFile> {
        val resp = dbx.filesDownload(json("path" to path)).await()
        val blob = resp.result.fileBlob
        val text = (blob.text() as kotlin.js.Promise<String>).await()
        return text to entryToFile(resp.result)
    }

    override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile {
        val mode = if (ifMatchRev != null) {
            json(".tag" to "update", "update" to ifMatchRev)
        } else {
            json(".tag" to "overwrite")
        }
        // Wrap text in a Blob: the browser SDK's documented upload form (a raw String can upload
        // empty/malformed in some SDK versions).
        val arg = json("path" to path, "contents" to Blob(arrayOf(content)), "mode" to mode, "mute" to true)
        try {
            val resp = dbx.filesUpload(arg).await()
            return entryToFile(resp.result)
        } catch (e: dynamic) {
            // A failed `update` is a write-conflict (someone else changed the rev).
            if (ifMatchRev != null && isConflict(e)) throw RevConflictException(path)
            throw Exception("Dropbox upload failed: ${describe(e)}")
        }
    }

    override suspend fun delete(path: String) {
        try {
            dbx.filesDeleteV2(json("path" to path)).await()
        } catch (e: dynamic) {
            if (!isNotFound(e)) throw Exception("Dropbox delete failed: ${describe(e)}")
        }
    }

    override suspend fun revoke() {
        // Best-effort: disconnect the app on Dropbox so a later Connect re-prompts for consent.
        runCatching { dbx.authTokenRevoke().await() }
    }

    // Dropbox JS SDK error shapes vary: most calls give { error: { error_summary } }, but some
    // (e.g. filesDownload) serialize the error as a string. Check all forms.
    private fun summaryOf(e: dynamic): String =
        (e?.error?.error_summary as? String)
            ?: (e?.error as? String)
            ?: (e?.message as? String)
            ?: ""

    private fun isConflict(e: dynamic): Boolean = summaryOf(e).contains("conflict")

    private fun isNotFound(e: dynamic): Boolean = summaryOf(e).contains("not_found")

    private fun describe(e: dynamic): String = summaryOf(e).ifEmpty { e.toString() }
}
