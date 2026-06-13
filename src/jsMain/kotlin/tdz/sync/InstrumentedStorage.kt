package tdz.sync

import kotlin.js.Date

/**
 * [RemoteStorage] decorator that times and logs every operation through [SyncLog]. Wrapped around
 * whichever backend the engine is built with ([tdz.sync.SyncController.buildEngine]), so one sync's
 * remote cost is visible per call AND aggregated in [SyncLog.summary] — the instrument for "this
 * sync takes forever". Note [columnVv]'s default implementation downloads the canvas manifest, so
 * a high columnVv count in the summary means per-column manifest fetches are the time sink.
 */
class InstrumentedStorage(private val delegate: RemoteStorage) : RemoteStorage {

    override suspend fun list(folder: String): RemoteListing =
        timed("list", "'$folder'", { "${it.files.size} file(s), rootExists=${it.rootExists}" }) {
            delegate.list(folder)
        }

    override suspend fun download(path: String): Pair<String, RemoteFile> =
        timed("download", path, { "${it.first.length} chars, rev=${it.second.rev}" }) {
            delegate.download(path)
        }

    override suspend fun upload(path: String, content: String, ifMatchRev: String?): RemoteFile =
        timed("upload", "$path (${content.length} chars, ifMatch=${ifMatchRev ?: "none"})", { "rev=${it.rev}" }) {
            delegate.upload(path, content, ifMatchRev)
        }

    override suspend fun delete(path: String): Unit =
        timed("delete", path, { "" }) { delegate.delete(path) }

    override suspend fun columnVv(path: String): VersionVector =
        timed("columnVv", path, { it.toString() }) { delegate.columnVv(path) }

    override suspend fun revoke() = delegate.revoke()   // one-off, not sync-path work

    override fun isReady(): Boolean = delegate.isReady()

    private suspend fun <T> timed(op: String, detail: String, result: (T) -> String, block: suspend () -> T): T {
        val t0 = Date.now()
        try {
            val r = block()
            val suffix = result(r).let { if (it.isEmpty()) "" else " → $it" }
            SyncLog.op(op, "$detail$suffix", Date.now() - t0)
            return r
        } catch (t: Throwable) {
            SyncLog.op(op, "$detail FAILED: ${t.message}", Date.now() - t0)
            throw t
        }
    }
}
