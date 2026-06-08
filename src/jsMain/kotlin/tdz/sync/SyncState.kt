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

/** Holds the current [SyncState]; [SyncController] is the only writer. */
object SyncStateStore {
    var current: SyncState = SyncState.NotConfigured
        private set

    fun set(state: SyncState, bus: EventBus) {
        current = state
        bus.fire(SyncStatusChanged(state))
    }
}
