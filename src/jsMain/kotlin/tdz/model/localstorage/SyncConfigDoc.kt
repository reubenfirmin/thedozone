package tdz.model.localstorage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * The remote backends the sync vertical can target. The user picks exactly one at a time. Defined in
 * the persistence layer because it is a stored config field (the wire format persists [name]); the sync
 * vertical re-exports it as `tdz.sync.SyncProvider`. So the model never depends on the sync layer.
 */
enum class SyncProvider(val displayName: String) {
    DROPBOX("Dropbox"),
    GOOGLE_DRIVE("Google Drive"),
    LOCAL_FOLDER("Local Folder"),
}

@Serializable
private data class SyncConfigWire(
    /** Legacy configs predate `provider`; absence means the only provider that existed: Dropbox. */
    val provider: String? = null,
    val enabled: Boolean,
    val rootFolder: String,
    val refreshToken: String? = null,
    val accountEmail: String? = null,
    val localFolderName: String? = null,
)

/**
 * Singleton sync configuration, shared by every [SyncProvider]. The Dropbox [refreshToken] is stored
 * here (XSS-exposed; unavoidable for a serverless SPA — App Folder scope limits the blast radius).
 * Google Drive stores NO long-lived secret (its access token lives in memory only); [accountEmail] is
 * its "previously connected" marker. The public app key/client id is NOT here — it is a build-time
 * constant ([tdz.sync.DropboxApp.KEY] / [tdz.sync.GoogleApp.CLIENT_ID]). rootFolder is an optional
 * subfolder/base folder (Dropbox: blank = app-folder root; Drive: blank = the default base folder).
 */
data class SyncConfigDoc(
    val provider: SyncProvider,
    val enabled: Boolean,
    val rootFolder: String,
    val refreshToken: String?,
    val accountEmail: String?,
    val localFolderName: String? = null,
) : HasKey {

    override val key: String get() = singletonKey()

    /** Provider-aware "has credentials" check. Drive has no persisted secret, so email marks it. */
    val connected: Boolean
        get() = when (provider) {
            SyncProvider.DROPBOX -> refreshToken != null
            SyncProvider.GOOGLE_DRIVE -> accountEmail != null
            SyncProvider.LOCAL_FOLDER -> localFolderName != null
        }

    /** True when a CLOUD provider is the active, connected backend (used by the Cloud Sync modal). */
    val cloudConnected: Boolean get() = provider != SyncProvider.LOCAL_FOLDER && connected

    /** True when the local folder is the active, connected backend (used by the Local Folder modal). */
    val localConnected: Boolean get() = provider == SyncProvider.LOCAL_FOLDER && localFolderName != null

    override fun encode(): String = storageJson.encodeToString(
        SyncConfigWire(provider.name, enabled, rootFolder, refreshToken, accountEmail, localFolderName)
    )

    companion object {
        /** The single localStorage key under [Namespace.SYNC_CONFIG] (one document, not per-canvas). */
        const val KEY = "config"

        fun singletonKey() = KEY

        val DISABLED = SyncConfigDoc(
            provider = SyncProvider.DROPBOX,
            enabled = false, rootFolder = "", refreshToken = null, accountEmail = null,
        )

        fun decode(raw: String): SyncConfigDoc {
            val wire = storageJson.decodeFromString<SyncConfigWire>(raw)
            return SyncConfigDoc(
                // Unknown/absent provider falls back to Dropbox (the original sole provider).
                provider = wire.provider?.let { p -> SyncProvider.entries.firstOrNull { it.name == p } }
                    ?: SyncProvider.DROPBOX,
                enabled = wire.enabled,
                rootFolder = wire.rootFolder,
                refreshToken = wire.refreshToken,
                accountEmail = wire.accountEmail,
                localFolderName = wire.localFolderName,
            )
        }
    }
}
