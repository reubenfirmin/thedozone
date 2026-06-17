package tdz.sync

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import web.blob.Blob
import kotlin.js.Date
import kotlin.js.json

/** A Dropbox write failed because too many writes hit the namespace at once (it serializes writes). */
internal fun isWriteRateLimited(errorSummary: String, status: Int?): Boolean =
    status == 429 ||
        errorSummary.contains("too_many_write_operations") ||
        errorSummary.contains("too_many_requests")

/** Backoff before retrying a rate-limited write: honor Dropbox's `retry_after` (seconds) when given,
 *  else exponential 250ms → 500 → 1000 … capped at 4s. */
internal fun writeRetryDelayMs(attempt: Int, retryAfterSec: Double?): Long =
    if (retryAfterSec != null && retryAfterSec > 0) (retryAfterSec * 1000).toLong()
    else (250L shl attempt).coerceAtMost(4000L)

private const val MAX_WRITE_RETRIES = 6

/**
 * [RemoteStorage] backed by the Dropbox SDK. Constructed with the stored [refreshToken]; the public
 * app key comes from the baked-in [DropboxApp.KEY]. The SDK mints/refreshes short-lived access tokens
 * automatically. All `dynamic` SDK responses are mapped to typed [RemoteFile]s here.
 */
class DropboxStorage(refreshToken: String) : RemoteStorage {

    private val dbx = DropboxSdk(
        json("clientId" to DropboxApp.KEY, "refreshToken" to refreshToken)
    )

    // Dropbox serializes writes per namespace and rejects concurrent ones with
    // too_many_write_operations. pushBatch fans out all uploads at once, so serialize them here
    // (one write in flight per client) to avoid the storm; a retry handles any residual contention.
    private val writeLock = Mutex()

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
        // NOT filesDownload: its content.dropboxapi.com file response is unreliable from the browser —
        // the successful 200 can arrive WITHOUT CORS headers (Dropbox's edge and/or HTTPS-inspecting
        // proxies), so the fetch fails even though the bytes are there. filesGetTemporaryLink is a
        // plain api.dropboxapi.com RPC returning a short-lived direct URL; we GET it with no custom
        // headers (a "simple" request, no fragile preflight) and take metadata from the RPC result.
        val resp = dbx.filesGetTemporaryLink(json("path" to path)).await()
        val link = resp.result.link as? String ?: error("Dropbox temporary link missing")
        val fileResp = window.fetch(link).await()
        if (!fileResp.ok) throw Exception("Dropbox download failed: HTTP ${fileResp.status}")
        val text = fileResp.text().await()
        return text to entryToFile(resp.result.metadata)
    }

    override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile = writeLock.withLock {
        val mode = if (ifMatchRev != null) {
            json(".tag" to "update", "update" to ifMatchRev)
        } else {
            json(".tag" to "overwrite")
        }
        // Wrap text in a Blob: the browser SDK's documented upload form (a raw String can upload
        // empty/malformed in some SDK versions).
        val arg = json("path" to path, "contents" to Blob(arrayOf(content)), "mode" to mode, "mute" to true)
        try {
            val resp = withWriteRetry { dbx.filesUpload(arg).await() }
            entryToFile(resp.result)
        } catch (e: dynamic) {
            // A failed `update` is a write-conflict (someone else changed the rev).
            if (ifMatchRev != null && isConflict(e)) throw RevConflictException(path)
            throw Exception("Dropbox upload failed: ${describe(e)}")
        }
    }

    override suspend fun delete(path: String): Unit = writeLock.withLock {
        try {
            withWriteRetry { dbx.filesDeleteV2(json("path" to path)).await() }
        } catch (e: dynamic) {
            if (!isNotFound(e)) throw Exception("Dropbox delete failed: ${describe(e)}")
        }
    }

    /** Retry a single write through the rate-limit backoff. Only [isWriteRateLimited] errors retry;
     *  conflicts and everything else propagate immediately to the caller's own handling. */
    private suspend fun <T> withWriteRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: dynamic) {
                if (!isWriteRateLimited(summaryOf(e), e?.status as? Int) || attempt >= MAX_WRITE_RETRIES) throw e
                delay(writeRetryDelayMs(attempt, retryAfterSecOf(e)))
                attempt++
            }
        }
    }

    private fun retryAfterSecOf(e: dynamic): Double? =
        (e?.error?.retry_after as? Number)?.toDouble()
            ?: (e?.error?.reason?.retry_after as? Number)?.toDouble()

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
