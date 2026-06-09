package tdz.sync

import kotlinx.browser.window
import kotlin.js.Date
import kotlin.js.json

/**
 * Pre-sync safety net: a snapshot of all app-owned localStorage keys, so a destructive reconcile is
 * recoverable. Snapshots live under [BACKUP_PREFIX]<id>; [META_KEY] holds the ordered list of ids
 * (oldest first). We keep the most recent [MAX_BACKUPS]. Backup keys and auth secrets are excluded so
 * a snapshot never captures tokens and never nests backups inside backups.
 */
object LocalBackup {

    const val MAX_BACKUPS = 3
    private const val BACKUP_PREFIX = "tdz.backup."
    private const val META_KEY = "tdz.backup.meta"
    // Auth secrets never belong in a data backup. Mirror the keys the auth modules persist
    // (DriveAuth.STORE_KEY, DropboxAuth's PKCE verifier).
    private val SECRET_KEYS = setOf("tdz.sync.driveToken", "tdz_dropbox_code_verifier")

    private var counter = 0

    /** Capture all app data into a new backup slot; returns its id, or null if nothing to back up / failed. */
    fun snapshot(): String? {
        return try {
            val ls = window.localStorage
            val data = json()
            var count = 0
            for (i in 0 until ls.length) {
                val k = ls.key(i) ?: continue
                if (k.startsWith(BACKUP_PREFIX) || k in SECRET_KEYS) continue
                data[k] = ls.getItem(k); count++
            }
            if (count == 0) return null
            val id = "${Date.now().toLong()}-${++counter}"
            ls.setItem("$BACKUP_PREFIX$id", JSON.stringify(data))
            // Commit slot -> meta + prune. If this fails (e.g. QuotaExceeded after the big slot write),
            // delete the just-written orphan slot so it can't leak a full copy of app data.
            try {
                val ids = list() + id
                ls.setItem(META_KEY, JSON.stringify(ids.toTypedArray()))
                prune(ids)
            } catch (e: Throwable) {
                runCatching { ls.removeItem("$BACKUP_PREFIX$id") }
                throw e
            }
            id
        } catch (e: Throwable) {
            console.warn("[backup] snapshot failed: ${e.message}")
            null
        }
    }

    /** Ordered backup ids (oldest first). */
    fun list(): List<String> = runCatching {
        val raw = window.localStorage.getItem(META_KEY) ?: return emptyList()
        (JSON.parse<Array<String>>(raw)).toList()
    }.getOrDefault(emptyList())

    /**
     * Restore every key from backup [id] over current localStorage. Returns true if the slot existed
     * and was applied; false if the slot was missing or the restore failed (logged).
     */
    fun restore(id: String): Boolean = runCatching {
        val raw = window.localStorage.getItem("$BACKUP_PREFIX$id") ?: return false
        val obj = JSON.parse<dynamic>(raw)
        val backupKeys = (js("Object").keys(obj) as Array<String>).toSet()
        val ls = window.localStorage
        // Remove app data the snapshot didn't have (true rollback), but keep secrets + backups.
        val toRemove = mutableListOf<String>()
        for (i in 0 until ls.length) {
            val k = ls.key(i) ?: continue
            if (k.startsWith(BACKUP_PREFIX) || k in SECRET_KEYS) continue
            if (k !in backupKeys) toRemove += k
        }
        toRemove.forEach { ls.removeItem(it) }
        // Replay the snapshot.
        backupKeys.forEach { k ->
            val v = obj[k]
            if (v != null && v != undefined) ls.setItem(k, v as String)
        }
        true
    }.getOrElse {
        console.warn("[backup] restore failed: ${it.message}")
        false
    }

    /** Convenience: the newest backup id, or null. */
    fun latest(): String? = list().lastOrNull()

    private fun prune(ids: List<String>) {
        val ls = window.localStorage
        if (ids.size <= MAX_BACKUPS) return
        val drop = ids.take(ids.size - MAX_BACKUPS)
        drop.forEach { ls.removeItem("$BACKUP_PREFIX$it") }
        ls.setItem(META_KEY, JSON.stringify(ids.drop(drop.size).toTypedArray()))
    }
}
