package tdz.sync

/**
 * The live [SyncEngine] plus the single reconcile/initial-sync gate every sync unit coordinates on.
 *
 * The lifecycle rebuilds [engine] on connect/disconnect/initial-sync; [SyncController.reconcile] and
 * the initial-sync flows ([InitialSync]) all run pulls/pushes that must NOT overlap against the same
 * engine and state. Holding [engine] (the single source of truth [PushScheduler]'s `{ engine }`
 * lambda and [SyncController.reconcile] read) and the gate here — rather than in any one unit — is
 * what lets them share it.
 *
 * The gate is encapsulated: callers can never set a naked flag, so a reconcile and a user-triggered
 * initial-sync can no longer both think they hold the engine. [tryBeginReconcile] takes the gate (and
 * MUST be called synchronously, before any `scope.launch`, so a rapid second caller observes it taken
 * and bails); [endReconcile] releases it (always from a `finally`, even if `fail()` throws).
 */
internal class SyncSession {
    /** The current [SyncEngine], rebuilt on connect/disconnect. Null when sync is not active. */
    var engine: SyncEngine? = null

    private var reconciling = false

    /**
     * Try to take the reconcile/initial-sync gate. Returns true and marks it taken if it was free,
     * false if a reconcile or initial-sync is already in flight. Echo protection for pulled manifests
     * is ORIGIN-based, not time-based (writeLocal fires RefreshCanvas with remote = true and the
     * structural subscriber skips those), so user actions during a reconcile are still pushed, not
     * dropped.
     */
    fun tryBeginReconcile(): Boolean {
        if (reconciling) return false
        reconciling = true
        return true
    }

    /** Release the gate. Call from a `finally` so a throwing `fail()` can't freeze every future run. */
    fun endReconcile() { reconciling = false }
}
