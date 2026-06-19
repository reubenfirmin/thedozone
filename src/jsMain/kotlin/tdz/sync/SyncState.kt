package tdz.sync

import tdz.controller.events.Event
import zoned.framework.events.EventBus
import tdz.controller.events.SyncStatusChanged

/** The single source of truth for sync UI. `locked` marks the "don't edit yet" window. */
sealed interface SyncState {
    object NotConfigured : SyncState
    object Connecting : SyncState
    /** Authenticated, but BOTH this device and the folder already have content — the user must pick
     *  the first-sync direction (Sync FROM = merge, Sync TO = replace) before any data moves. */
    object ChoosingDirection : SyncState
    object Reconciling : SyncState
    object Synced : SyncState
    object Syncing : SyncState
    object Offline : SyncState
    object NeedsReconnect : SyncState
    data class Error(val message: String) : SyncState
}

val SyncState.locked: Boolean
    get() = this is SyncState.Connecting || this is SyncState.Reconciling

/**
 * Map a thrown sync failure to the state the UI should show. A failed silent Google token mint
 * ([NeedsInteractiveAuthException]) is an EXPECTED, periodic event — the GIS token model has no
 * refresh token, so the Google session lapses and must be re-granted by a user gesture — so it
 * surfaces as the calm [NeedsReconnect], NOT an [Error]. A request that never reached the server
 * (offline, an extension/proxy/DNS block, a dropped connection) is a transient reachability problem,
 * not a real fault, so it surfaces as the calm [Offline] (the next reconcile retries). Everything
 * else (including genuine Dropbox auth failures, which throw a plain exception) is a real [Error].
 */
fun syncFailureState(t: Throwable): SyncState = when {
    t is NeedsInteractiveAuthException -> SyncState.NeedsReconnect
    isUnreachable(t) -> SyncState.Offline
    else -> SyncState.Error(t.message ?: "sync error")
}

/**
 * A `fetch` that never reached the server throws a browser-specific TypeError: "Failed to fetch"
 * (Chrome), "NetworkError when attempting to fetch resource" (Firefox), "Load failed" (Safari).
 * Match those wordings to tell a reachability blip apart from a genuine API/auth error.
 */
private fun isUnreachable(t: Throwable): Boolean {
    val m = (t.message ?: "").lowercase()
    return "failed to fetch" in m || "networkerror" in m || "network error" in m || "load failed" in m
}

/** Holds the current [SyncState]; [SyncController] is the only writer. */
object SyncStateStore {
    var current: SyncState = SyncState.NotConfigured
        private set

    fun set(state: SyncState, bus: EventBus<Event>) {
        current = state
        bus.fire(SyncStatusChanged(state))
    }
}
