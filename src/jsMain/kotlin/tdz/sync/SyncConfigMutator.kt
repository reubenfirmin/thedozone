package tdz.sync

import tdz.model.Model
import tdz.model.localstorage.SyncStateDoc

/**
 * The pure, lifecycle-independent config writes the modal makes BEFORE connecting: choosing a
 * provider and typing a root folder. Both only persist config (and, for a provider change, drop the
 * stale bookkeeping) — no engine, no scope, no callbacks. The lifecycle-coupled mutators that must
 * trigger a (re)sync ([ConnectionLifecycle.setEnabled], [ConnectionLifecycle.changeRootFolder]) stay
 * in the lifecycle.
 */
internal class SyncConfigMutator(
    private val model: Model,
    private val access: SyncStateAccess,
) {
    /** Persist the chosen provider before connecting (the modal calls this on selection). */
    fun setProvider(provider: SyncProvider) {
        if (provider == access.config().provider) return
        model.writeSyncConfig(access.config().copy(provider = provider))
        // A different provider's remote shares no history — drop the bookkeeping so connect() treats
        // it as a genuinely fresh target (no false "has synced before").
        access.saveState(SyncStateDoc.EMPTY)
    }

    fun setRootFolder(folder: String) {
        model.writeSyncConfig(access.config().copy(rootFolder = folder))
    }
}
