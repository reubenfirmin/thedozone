package tdz.sync

import kotlin.js.Date

/**
 * Console-visible sync instrumentation, gated by [enabled] — Main sets it from the `?debug` URL
 * flag, so live syncs are instrumentable on demand without cluttering a normal load. (Default true
 * so unit tests capture output via [sink]; the app turns it off unless ?debug.)
 *
 * Two layers: [log] for phase narration (reconcile started/pull/push/finished), and [op] for
 * timed remote work (one storage call, one Drive HTTP request), which also aggregates into
 * [summary] — the single line that says where a slow sync's time went, e.g.
 * `14× columnVv (8200ms), 6× download (2900ms), 1× list (600ms)`.
 *
 * Counters are a single window shared by whatever sync work is in flight (reconcile + concurrent
 * debounced pushes can interleave); that imprecision is fine for instrumentation.
 */
object SyncLog {

    var enabled = true

    /** Test seam: capture lines instead of writing to the console. */
    internal var sink: (String) -> Unit = { console.info(it) }

    private val counts = mutableMapOf<String, Int>()
    private val totalMs = mutableMapOf<String, Double>()

    fun log(msg: String) {
        if (enabled) sink("[sync] $msg")
    }

    /** Record + log one timed unit of remote work under [op], aggregated into [summary]. */
    fun op(op: String, detail: String, ms: Double) {
        counts[op] = (counts[op] ?: 0) + 1
        totalMs[op] = (totalMs[op] ?: 0.0) + ms
        log("$op $detail (${ms.toInt()}ms)")
    }

    /** Start a fresh aggregation window (called at the top of a reconcile / push burst). */
    fun resetCounters() {
        counts.clear()
        totalMs.clear()
    }

    /** The current window's per-op tally: `"3× download (1200ms), 1× list (300ms)"`. */
    fun summary(): String =
        counts.keys.sorted()
            .joinToString(", ") { k -> "${counts[k]}× $k (${(totalMs[k] ?: 0.0).toInt()}ms)" }
            .ifEmpty { "no remote calls" }

    /** Milliseconds since [t0] as display text. */
    fun since(t0: Double): String = "${(Date.now() - t0).toInt()}ms"
}
