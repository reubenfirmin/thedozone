package tdz.sync

import tdz.controller.events.EventBus
import tdz.controller.events.SyncStatusChanged

/** The single source of truth for sync UI. `locked` marks the "don't edit yet" window. */
sealed interface SyncState {
    object NotConfigured : SyncState
    object Connecting : SyncState
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
 * surfaces as the calm [NeedsReconnect], NOT an [Error]. Everything else (including genuine Dropbox
 * auth failures, which throw a plain exception) is a real [Error].
 */
fun syncFailureState(t: Throwable): SyncState =
    if (t is NeedsInteractiveAuthException) SyncState.NeedsReconnect
    else SyncState.Error(t.message ?: "sync error")

/** Holds the current [SyncState]; [SyncController] is the only writer. */
object SyncStateStore {
    var current: SyncState = SyncState.NotConfigured
        private set

    fun set(state: SyncState, bus: EventBus) {
        current = state
        bus.fire(SyncStatusChanged(state))
    }
}
