package tdz.sync

import tdz.model.Model
import tdz.model.localstorage.SyncStateDoc

/**
 * The one place every sync unit reads/writes the persisted sync config and state through the
 * [Model]. Previously [config]/[state]/[saveState]/[root] were duplicated verbatim in
 * [SyncController] AND the connection lifecycle; collapsing them here removes the drift risk and is
 * the natural home for the [SyncPaths.rootPrefix] normalization (a raw config string is an invalid
 * Dropbox `list_folder` path when non-empty and missing a leading slash).
 */
internal class SyncStateAccess(private val model: Model) {
    fun config() = model.readSyncConfig()
    fun state() = model.readSyncState()
    fun saveState(s: SyncStateDoc) = model.writeSyncState(s)

    /** Normalized path prefix ("" or "/folder") — never the raw config string. */
    fun root() = SyncPaths.rootPrefix(config().rootFolder)
}
