package tdz.sync

import tdz.model.localstorage.SyncConfigDoc

/** What an interactive connect yields for persistence: a display email and (Dropbox only) a secret. */
data class ConnectResult(val accountEmail: String?, val refreshToken: String?)

/**
 * The one place that knows which concrete [RemoteStorage]/auth a [SyncProvider] uses. Everything above
 * this (engine, controller logic, UI) is provider-agnostic. Adding a backend = adding a branch here.
 */
object SyncProviders {

    /**
     * The default root folder when the user leaves it blank. Both cloud providers default to a concrete
     * subfolder so a blank field still syncs into a clean namespace instead of the provider root: for
     * Dropbox a blank folder is the shared app-folder ROOT (which litters/collides — and the safeguard
     * refuses it), and a blank Drive root would scatter canvases loose at the Drive root. A local folder
     * needs none — the granted directory IS the root.
     */
    fun defaultRoot(provider: SyncProvider): String = when (provider) {
        SyncProvider.DROPBOX -> "thedozone"
        SyncProvider.GOOGLE_DRIVE -> "thedozone"
        SyncProvider.LOCAL_FOLDER -> ""   // the granted directory IS the root
    }

    /** Run the provider's interactive connect (consent popup). */
    suspend fun connect(provider: SyncProvider): ConnectResult = when (provider) {
        SyncProvider.DROPBOX ->
            DropboxAuth.connect().let { ConnectResult(it.accountEmail, it.refreshToken) }
        SyncProvider.GOOGLE_DRIVE ->
            DriveAuth.connect().let { ConnectResult(it.accountEmail, refreshToken = null) }
        // Local folder is NOT OAuth-shaped: it is connected via SyncController.connectLocalFolder()
        // (directory picker + IndexedDB handle), never through this path.
        SyncProvider.LOCAL_FOLDER ->
            throw IllegalStateException("LOCAL_FOLDER connects via SyncController.connectLocalFolder()")
    }

    /** Build storage from persisted config, or null if it cannot be built (not connected). */
    fun storage(config: SyncConfigDoc): RemoteStorage? = when (config.provider) {
        SyncProvider.DROPBOX -> config.refreshToken?.let { DropboxStorage(it) }
        SyncProvider.GOOGLE_DRIVE -> if (config.accountEmail != null) DriveStorage() else null
        // Local folder storage needs the live FileSystemDirectoryHandle (loaded async from IndexedDB),
        // which this synchronous, config-only factory cannot supply. SyncController builds it directly.
        SyncProvider.LOCAL_FOLDER -> null
    }
}
