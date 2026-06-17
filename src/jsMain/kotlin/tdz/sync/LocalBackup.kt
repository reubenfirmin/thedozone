package tdz.sync

import kotlinx.browser.window
import kotlin.js.Date
import kotlin.js.json

/**
 * The 30-minute rolling restore point: a single slot holding a snapshot of all app-owned
 * localStorage, refreshed periodically by [PeriodicBackup] and rolled back per-board via
 * [SyncController.restoreAutoBackup]. Backup keys and auth secrets are excluded, so a snapshot never
 * captures tokens and never nests a backup inside itself.
 */
object LocalBackup {

    // The single rolling "restore point" slot. Both the slot and its timestamp sit under this prefix
    // so capture() never folds them back into the next snapshot.
    private const val BACKUP_PREFIX = "tdz.backup."
    private const val AUTO_SLOT = "tdz.backup.auto"
    private const val AUTO_TS = "tdz.backup.auto.ts"
    // Auth secrets never belong in a data backup. Mirror the keys the auth modules persist
    // (DriveAuth.STORE_KEY, DropboxAuth's PKCE verifier).
    private val SECRET_KEYS = setOf("tdz.sync.driveToken", "tdz_dropbox_code_verifier")

    /** All app-owned data as one JSON string, or null when there is nothing to back up. */
    private fun capture(): String? {
        tdz.model.localstorage.flushPendingWrites()   // an unflushed edit must not be missed
        val ls = window.localStorage
        val data = json()
        var count = 0
        for (i in 0 until ls.length) {
            val k = ls.key(i) ?: continue
            if (k.startsWith(BACKUP_PREFIX) || k in SECRET_KEYS) continue
            data[k] = ls.getItem(k); count++
        }
        return if (count == 0) null else JSON.stringify(data)
    }

    /** Refresh THE periodic backup slot (one slot, overwritten). False if nothing to back up / failed. */
    fun autoSnapshot(): Boolean = try {
        val raw = capture()
        if (raw == null) false else {
            window.localStorage.setItem(AUTO_SLOT, raw)
            window.localStorage.setItem(AUTO_TS, Date.now().toString())
            true
        }
    } catch (e: Throwable) {
        console.warn("[backup] auto snapshot failed: ${e.message}")
        false
    }

    /** When the periodic backup was taken (epoch ms), or null when none exists. */
    fun autoTimestamp(): Double? =
        window.localStorage.getItem(AUTO_TS)?.toDoubleOrNull()
            ?.takeIf { window.localStorage.getItem(AUTO_SLOT) != null }

    /** The backed-up value of one [key] in the periodic slot, or null (no slot / key absent). */
    fun autoValue(key: String): String? = runCatching {
        val raw = window.localStorage.getItem(AUTO_SLOT) ?: return null
        val v = JSON.parse<dynamic>(raw)[key]
        if (v == null || v == undefined) null else v as String
    }.getOrNull()

    /**
     * Restore ONLY [keys] from the periodic slot: each is replayed when the backup has it and
     * REMOVED when it doesn't (a true rollback of exactly that subset). Every other key — other
     * canvases, the sync bookkeeping, the theme — is untouched: restore is scoped to one board,
     * and the live sync vectors must survive so the restored content can be re-stamped as
     * causally newest and pushed up (replaying OLD bookkeeping would make the remote look ahead
     * and the next pull would undo the restore).
     */
    fun restoreAutoKeys(keys: Set<String>): Boolean = runCatching {
        val raw = window.localStorage.getItem(AUTO_SLOT) ?: return false
        tdz.model.localstorage.flushPendingWrites()   // a buffered write must not clobber the restore
        val obj = JSON.parse<dynamic>(raw)
        keys.forEach { k ->
            val v = obj[k]
            if (v == null || v == undefined) window.localStorage.removeItem(k)
            else window.localStorage.setItem(k, v as String)
        }
        true
    }.getOrElse {
        console.warn("[backup] scoped restore failed: ${it.message}")
        false
    }
}
