package tdz.model.localstorage

import tdz.sync.SyncProvider
import kotlin.js.Json
import kotlin.js.json

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

    override fun toJson(): Json = json(
        "provider" to provider.name,
        "enabled" to enabled,
        "rootFolder" to rootFolder,
        "refreshToken" to refreshToken,
        "accountEmail" to accountEmail,
        "localFolderName" to localFolderName,
    )

    companion object {
        fun singletonKey() = "config"

        val DISABLED = SyncConfigDoc(
            provider = SyncProvider.DROPBOX,
            enabled = false, rootFolder = "", refreshToken = null, accountEmail = null,
        )

        fun fromJson(json: Json) = SyncConfigDoc(
            // Legacy configs predate `provider`; absence means the only provider that existed: Dropbox.
            provider = (json["provider"] as? String)?.let { SyncProvider.valueOf(it) } ?: SyncProvider.DROPBOX,
            enabled = json["enabled"] as Boolean,
            rootFolder = json["rootFolder"] as String,
            refreshToken = json["refreshToken"] as String?,
            accountEmail = json["accountEmail"] as String?,
            localFolderName = json["localFolderName"] as String?,
        )
    }
}
