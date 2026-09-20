package tdz.sync

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.w3c.fetch.RequestInit
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

/**
 * True when [errorSummary] carries [tag] as a WHOLE slash-delimited segment. Dropbox encodes error
 * tags as a slash path terminated by `/.` (e.g. `path_lookup/not_found/.`), so a plain `contains`
 * would also fire on an unrelated token that merely embeds the tag (`not_found_in_index`) and wrongly
 * swallow a real failure. Splitting on `/` and `.` isolates the exact tags.
 */
internal fun errorSummaryHasTag(errorSummary: String, tag: String): Boolean =
    errorSummary.split('/', '.').any { it == tag }

/**
 * A remote delete that can be safely treated as a no-op so it never wedges the whole reconcile:
 * the file is already gone (`not_found`), or the path can't refer to a real remote file at all
 * (`malformed_path` — a poisoned `//`-prefixed key left by the old root-canvas path bug). Swallowing
 * these lets [SyncEngine.deleteRemoteBatch] drop the bookkeeping so the stale key self-heals. Real
 * failures (write conflicts, auth) are NOT ignorable and must fail the reconcile.
 */
internal fun isIgnorableDeleteError(errorSummary: String): Boolean =
    errorSummaryHasTag(errorSummary, "not_found") || errorSummaryHasTag(errorSummary, "malformed_path")

private const val MAX_WRITE_RETRIES = 6

/**
 * Map a Dropbox FileMetadata (the untyped SDK/RPC response) to a typed [RemoteFile]. Validates each
 * field rather than blind-casting: a missing/renamed field fails loudly here at the interop boundary
 * instead of surfacing as a null-as-String corruption deep in sync (this is exactly the shape that
 * broke uploads — the temp-upload-link body carries only a content-hash, so the upload path fetches
 * the real metadata separately). Uses path_display (original case): SyncPaths preserves canvas-name
 * case, so pulled paths must round-trip to the same model key (path_lower would break that).
 */
internal fun dropboxEntryToFile(e: dynamic): RemoteFile = RemoteFile(
    path = e.path_display as? String ?: error("Dropbox entry missing path_display"),
    rev = e.rev as? String ?: error("Dropbox entry missing rev"),
    serverModified = Date(e.server_modified as? String ?: error("Dropbox entry missing server_modified")).getTime(),
)

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

    private fun entryToFile(e: dynamic): RemoteFile = dropboxEntryToFile(e)

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
            // Validate the untyped SDK response rather than blind-casting (see [entryToFile]).
            val entries = resp.result.entries as? Array<dynamic> ?: error("Dropbox list missing entries")
            entries.forEach { if (it[".tag"] == "file") files += entryToFile(it) }
            if (resp.result.has_more as? Boolean == true) {
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
        // Do NOT call dbx.filesUpload: it POSTs to content.dropboxapi.com/2/files/upload with custom
        // headers (Authorization, Dropbox-API-Arg), and that RPC endpoint's CORS preflight fails from
        // the browser — the same unreliability download() routes around. Instead mint a pre-authorized
        // upload URL via the api.dropboxapi.com RPC (which works), then POST the bytes straight to the
        // returned /apitul/ link. That temp-upload endpoint IS built for browser uploads: it answers
        // CORS (including the octet-stream preflight) and requires Content-Type application/octet-stream
        // (it rejects text/plain). The commit info is baked into the link, so no auth/arg headers here.
        val commitInfo = json("path" to path, "mode" to mode, "mute" to true)
        try {
            withWriteRetry {
                val link = dbx.filesGetTemporaryUploadLink(json("commit_info" to commitInfo)).await()
                    .result.link as? String ?: error("Dropbox temporary upload link missing")
                val resp = window.fetch(link, RequestInit(
                    method = "POST",
                    body = content,
                    headers = json("Content-Type" to "application/octet-stream"),
                )).await()
                if (resp.ok) {
                    // The temp-upload-link POST returns only a content-hash, not the committed
                    // metadata (and any Dropbox-API-Result header isn't CORS-exposed to JS). Fetch the
                    // canonical FileMetadata via the api.dropboxapi.com RPC — which works from the
                    // browser — to get the rev (+ server_modified) the bookkeeping needs.
                    entryToFile(dbx.filesGetMetadata(json("path" to path)).await().result)
                } else {
                    // Dropbox returns the error JSON in the body. A rev-mismatch in `update` mode is a
                    // write-conflict (caller 3-way merges); rate-limit tags are retried by
                    // withWriteRetry (it re-reads this message); anything else fails the reconcile
                    // loudly — never a silent success that could lose data.
                    val body = runCatching { resp.text().await() }.getOrDefault("HTTP ${resp.status}")
                    if (ifMatchRev != null && body.contains("conflict")) throw RevConflictException(path)
                    throw Exception(body)
                }
            }
        } catch (e: dynamic) {
            if (e is RevConflictException) throw e
            throw Exception("Dropbox upload failed: ${describe(e)}")
        }
    }

    override suspend fun delete(path: String): Unit = writeLock.withLock {
        try {
            withWriteRetry { dbx.filesDeleteV2(json("path" to path)).await() }
        } catch (e: dynamic) {
            // Swallow deletes that can't refer to a real file (already gone, or a malformed
            // `//`-prefixed key from the old root-canvas bug): failing here would wedge every
            // reconcile in Error. The caller drops the bookkeeping, so the stale key self-heals.
            if (!isIgnorableDeleteError(summaryOf(e))) throw Exception("Dropbox delete failed: ${describe(e)}")
            else console.warn("[sync] ignoring un-deletable path '$path': ${describe(e)}")
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

    // Whole-segment match (see [errorSummaryHasTag]): a loose substring here would misread an
    // unrelated error as "folder missing" and make list() return empty — which pull() reads as
    // mass remote-deletion and wipes local columns.
    private fun isNotFound(e: dynamic): Boolean = errorSummaryHasTag(summaryOf(e), "not_found")

    private fun describe(e: dynamic): String = summaryOf(e).ifEmpty { e.toString() }
}
